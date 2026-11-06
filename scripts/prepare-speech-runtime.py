#!/usr/bin/env python3
"""Fetch the pinned upstream Android runtime; no model or user content is bundled."""
import hashlib
import shutil
import tarfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VERSION = "1.12.21"
SHA256 = "3ef3cb567669c274c896692821d47ee92b7ab7f11c2da566fc77a10c2ead57b5"
CACHE = ROOT / ".build-cache/speech"
ARCHIVE = CACHE / f"sherpa-onnx-v{VERSION}-android.tar.bz2"
DEST = CACHE / f"runtime-{VERSION}"
ABIS = ("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
LIBS = ("libsherpa-onnx-c-api.so", "libonnxruntime.so")


def main():
    CACHE.mkdir(parents=True, exist_ok=True)
    if not ARCHIVE.exists():
        pending = ARCHIVE.with_suffix(".part")
        try:
            with urllib.request.urlopen(
                f"https://github.com/k2-fsa/sherpa-onnx/releases/download/v{VERSION}/{ARCHIVE.name}",
                timeout=60,
            ) as source, pending.open("wb") as target:
                shutil.copyfileobj(source, target)
            pending.replace(ARCHIVE)
        finally:
            pending.unlink(missing_ok=True)
    if hashlib.sha256(ARCHIVE.read_bytes()).hexdigest() != SHA256:
        raise RuntimeError(f"Runtime checksum mismatch. Remove {ARCHIVE} and retry.")
    with tarfile.open(ARCHIVE) as archive:
        for abi in ABIS:
            for lib in LIBS:
                name = f"jniLibs/{abi}/{lib}"
                member = archive.getmember("./" + name)
                if not member.isfile() or member.size > 50 * 1024 * 1024:
                    raise RuntimeError("Unexpected runtime entry")
                dest = DEST / name
                dest.parent.mkdir(parents=True, exist_ok=True)
                with archive.extractfile(member) as source, dest.open("wb") as target:
                    shutil.copyfileobj(source, target)
    print(f"Verified sherpa-onnx {VERSION}: {', '.join(ABIS)}")


if __name__ == "__main__":
    main()
