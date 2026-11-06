#!/usr/bin/env python3
"""Create the first release identity once; never rotate or overwrite an existing key."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, default=ROOT / "local-test-data/signing/release")
    args = parser.parse_args()
    directory = args.directory.resolve()
    certificate = ROOT / "docs/signing/release-certificate.pem"
    if certificate.exists() or directory.exists():
        raise SystemExit("Release identity already exists. Restore its backup; do not generate another key.")
    keytool = shutil.which("keytool")
    if not keytool:
        raise SystemExit("JDK 17 keytool is required.")
    os.umask(0o077)
    directory.mkdir(parents=True, mode=0o700)
    credentials = {
        "keystore": "shufangtv-release.p12",
        "alias": "shufangtv-release",
        "storePassword": secrets.token_urlsafe(48),
    }
    credentials["keyPassword"] = credentials["storePassword"]
    # Write recovery information before invoking keytool; never print it or pass it in argv.
    (directory / "credentials.json").write_text(json.dumps(credentials, indent=2) + "\n")
    env = os.environ.copy()
    env["SHUFANG_KEY_PASSWORD"] = credentials["storePassword"]
    keystore = directory / credentials["keystore"]
    subprocess.run([
        keytool, "-genkeypair", "-noprompt", "-storetype", "PKCS12", "-keystore", str(keystore),
        "-alias", credentials["alias"], "-keyalg", "RSA", "-keysize", "3072", "-sigalg", "SHA256withRSA",
        "-validity", "10950", "-dname", "CN=ShufangTV Release, OU=Open Source, O=spudhero",
        "-storepass:env", "SHUFANG_KEY_PASSWORD", "-keypass:env", "SHUFANG_KEY_PASSWORD",
    ], env=env, check=True)
    common = [keytool, "-exportcert", "-keystore", str(keystore), "-alias", credentials["alias"],
              "-storepass:env", "SHUFANG_KEY_PASSWORD"]
    der = subprocess.check_output(common, env=env)
    pem = subprocess.check_output([*common, "-rfc"], env=env)
    certificate.parent.mkdir(parents=True, exist_ok=True)
    certificate.write_text(pem.decode("ascii").replace("\r\n", "\n"))
    certificate.chmod(0o644)
    digest = hashlib.sha256(der).hexdigest()
    certificate.with_suffix(".sha256").write_text(digest + "\n")
    certificate.with_suffix(".sha256").chmod(0o644)
    print("Release key created in:", directory)
    print("Public certificate SHA-256:", digest)
    print("Back up the PKCS12 file and credentials.json together in a separate secure location.")


if __name__ == "__main__":
    main()
