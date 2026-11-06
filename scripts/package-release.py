#!/usr/bin/env python3
"""Align, sign and verify the unsigned release APK without sending secrets to Gradle."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import ssl
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ABIS = {"armeabi-v7a", "arm64-v8a", "x86", "x86_64"}
NATIVE_LIBS = {"libmobi.so", "libshufang_mobi.so", "libshufang_speech.so",
               "libsherpa-onnx-c-api.so", "libonnxruntime.so"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, default=ROOT / "app/build/outputs/apk/release/app-release-unsigned.apk")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/release")
    parser.add_argument("--signing-directory", type=Path, help="Local private directory containing credentials.json")
    args = parser.parse_args()
    if args.signing_directory:
        credentials = json.loads((args.signing_directory / "credentials.json").read_text())
        os.environ.update({
            "ANDROID_RELEASE_KEYSTORE_PATH": str(args.signing_directory / credentials["keystore"]),
            "ANDROID_RELEASE_STORE_PASSWORD": credentials["storePassword"],
            "ANDROID_RELEASE_KEY_ALIAS": credentials["alias"],
            "ANDROID_RELEASE_KEY_PASSWORD": credentials["keyPassword"],
        })
    names = ["ANDROID_RELEASE_KEYSTORE_PATH", "ANDROID_RELEASE_STORE_PASSWORD",
             "ANDROID_RELEASE_KEY_ALIAS", "ANDROID_RELEASE_KEY_PASSWORD"]
    missing = [name for name in names if not os.environ.get(name)]
    if missing:
        raise SystemExit("Missing signing configuration: " + ", ".join(missing))
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise SystemExit("ANDROID_HOME or ANDROID_SDK_ROOT is required.")
    tools = Path(sdk) / "build-tools/36.0.0"
    if not args.apk.is_file():
        raise SystemExit("Build :app:assembleRelease before packaging.")
    badging = subprocess.check_output([str(tools / "aapt"), "dump", "badging", str(args.apk)], text=True)
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
    if not package or package[1] != "io.github.spudhero.shufangtv" or "application-debuggable" in badging:
        raise SystemExit("Expected a non-debuggable ShufangTV release APK.")
    version = package[3]
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?", version):
        raise SystemExit("Unexpected version name.")
    if os.environ.get("GITHUB_REF_TYPE") == "tag" and os.environ.get("GITHUB_REF_NAME") != "v" + version:
        raise SystemExit("Release tag must equal v + the APK versionName.")
    with zipfile.ZipFile(args.apk) as archive:
        entries = set(archive.namelist())
        abis = {name.split("/")[1] for name in entries if name.startswith("lib/") and name.endswith(".so")}
    if abis != ABIS:
        raise SystemExit("Release must contain all four supported ABIs.")
    if any(f"lib/{abi}/{library}" not in entries for abi in ABIS for library in NATIVE_LIBS):
        raise SystemExit("Release is missing a required native library.")
    expected = (ROOT / "docs/signing/release-certificate.sha256").read_text().strip()
    keystore = os.environ["ANDROID_RELEASE_KEYSTORE_PATH"]
    der = subprocess.check_output([
        shutil.which("keytool") or "keytool", "-exportcert", "-keystore", keystore,
        "-alias", os.environ["ANDROID_RELEASE_KEY_ALIAS"],
        "-storepass:env", "ANDROID_RELEASE_STORE_PASSWORD",
    ])
    pem = (ROOT / "docs/signing/release-certificate.pem").read_text()
    if hashlib.sha256(der).hexdigest() != expected or hashlib.sha256(ssl.PEM_cert_to_DER_cert(pem)).hexdigest() != expected:
        raise SystemExit("Signing identity differs from the committed public certificate.")
    args.output.mkdir(parents=True, exist_ok=True)
    apk = args.output / f"ShufangTV-{version}-release.apk"
    with tempfile.TemporaryDirectory(prefix="shufang-sign-") as temp:
        aligned = Path(temp) / "aligned.apk"
        subprocess.run([str(tools / "zipalign"), "-P", "16", "-f", "4", str(args.apk), str(aligned)], check=True)
        subprocess.run([
            str(tools / "apksigner"), "sign", "--ks", keystore,
            "--ks-key-alias", os.environ["ANDROID_RELEASE_KEY_ALIAS"],
            "--ks-pass", "env:ANDROID_RELEASE_STORE_PASSWORD", "--key-pass", "env:ANDROID_RELEASE_KEY_PASSWORD",
            "--v1-signing-enabled", "false", "--v2-signing-enabled", "true", "--v3-signing-enabled", "true",
            "--v4-signing-enabled", "false", "--out", str(apk), str(aligned),
        ], check=True)
    verification = subprocess.check_output([
        str(tools / "apksigner"), "verify", "--verbose", "--print-certs", "--min-sdk-version", "26", str(apk)
    ], text=True)
    actual = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)", verification)
    if not actual or actual[1].lower() != expected:
        apk.unlink(missing_ok=True)
        raise SystemExit("Signed APK certificate mismatch.")
    subprocess.run([str(tools / "zipalign"), "-c", "-P", "16", "4", str(apk)], check=True)
    shutil.copyfile(ROOT / "docs/signing/release-certificate.pem", args.output / "release-certificate.pem")
    metadata = {
        "packageName": package[1], "versionCode": int(package[2]), "versionName": version,
        "commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "abis": sorted(abis), "debuggable": False, "signingCertificateSha256": expected,
        "apk": apk.name, "apkSha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
    }
    (args.output / "release-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
    names = [apk.name, "release-metadata.json", "release-certificate.pem"]
    (args.output / "SHA256SUMS").write_text("".join(
        f"{hashlib.sha256((args.output / name).read_bytes()).hexdigest()}  {name}\n" for name in names
    ))
    print(verification.strip())
    print("Verified signed release:", apk)
    print("APK SHA-256:", metadata["apkSha256"])


if __name__ == "__main__":
    main()
