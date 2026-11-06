#!/usr/bin/env python3
"""Install the existing release identity in this repository's GitHub Actions secrets."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = "spudhero/ShufangTV"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, default=ROOT / "local-test-data/signing/release")
    parser.add_argument("--update-existing", action="store_true", help="Reinstall the same identity's secrets")
    args = parser.parse_args()
    credentials = json.loads((args.directory / "credentials.json").read_text())
    keystore = args.directory / credentials["keystore"]
    env = os.environ.copy()
    env["GH_TOKEN"] = subprocess.check_output(
        ["gh", "auth", "token", "--hostname", "github.com", "--user", "spudhero"], text=True
    ).strip()
    account = subprocess.check_output(["gh", "api", "user", "--jq", ".login"], env=env, text=True).strip()
    if account != "spudhero":
        raise SystemExit("GitHub publication must use the spudhero account.")
    env["SHUFANG_KEY_PASSWORD"] = credentials["storePassword"]
    der = subprocess.check_output([
        shutil.which("keytool") or "keytool", "-exportcert", "-keystore", str(keystore),
        "-alias", credentials["alias"], "-storepass:env", "SHUFANG_KEY_PASSWORD",
    ], env=env)
    expected = (ROOT / "docs/signing/release-certificate.sha256").read_text().strip()
    if hashlib.sha256(der).hexdigest() != expected:
        raise SystemExit("Keystore does not match the committed release certificate.")
    values = {
        "ANDROID_RELEASE_KEYSTORE_BASE64": base64.b64encode(keystore.read_bytes()),
        "ANDROID_RELEASE_STORE_PASSWORD": credentials["storePassword"].encode(),
        "ANDROID_RELEASE_KEY_ALIAS": credentials["alias"].encode(),
        "ANDROID_RELEASE_KEY_PASSWORD": credentials["keyPassword"].encode(),
    }
    existing = json.loads(subprocess.check_output(
        ["gh", "secret", "list", "--repo", REPOSITORY, "--json", "name"], env=env, text=True
    ))
    if not args.update_existing and {x["name"] for x in existing}.intersection(values):
        raise SystemExit("Signing secrets already exist; use --update-existing only to restore this same key.")
    for name, value in values.items():
        subprocess.run(["gh", "secret", "set", name, "--repo", REPOSITORY], input=value, env=env, check=True)
        print("Configured", name)


if __name__ == "__main__":
    main()
