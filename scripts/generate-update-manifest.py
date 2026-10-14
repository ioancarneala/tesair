#!/usr/bin/env python3
"""Generate update.json from the exact signed APK attached to a stable GitHub release."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess

PACKAGE = "co.tesair.app"


def inspect_apk(apk: Path, aapt: str, apksigner: str) -> dict:
    badging = subprocess.run([aapt, "dump", "badging", str(apk)], check=True,
                             capture_output=True, text=True).stdout
    package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging, re.M)
    if package is None or package.group(1) != PACKAGE:
        raise ValueError("Expected the stable co.tesair.app APK")
    version = package.group(3)
    if re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version) is None:
        raise ValueError("A stable release version is required")
    signed = subprocess.run([apksigner, "verify", "--verbose", "--print-certs", str(apk)], check=True,
                            capture_output=True, text=True).stdout
    certificates = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest: ([a-fA-F0-9]{64})$", signed, re.M)
    if len(certificates) != 1:
        raise ValueError("Expected exactly one verified APK signing identity")
    with apk.open("rb") as stream:
        checksum = hashlib.file_digest(stream, "sha256").hexdigest()
    return {
        "schemaVersion": 1,
        "packageName": PACKAGE,
        "versionCode": int(package.group(2)),
        "versionName": version,
        "apkUrl": f"https://github.com/ioancarneala/tesair/releases/download/v{version}/TesAir-v{version}.apk",
        "sha256": checksum,
        "signingCertificateSha256": certificates[0].lower(),
        "notes": "",
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--output", type=Path, default=Path("update.json"))
    parser.add_argument("--aapt", default=shutil.which("aapt"))
    parser.add_argument("--apksigner", default=shutil.which("apksigner"))
    parser.add_argument("--notes", type=Path, help="Optional plain-text release summary")
    args = parser.parse_args()
    if not args.aapt or not args.apksigner:
        parser.error("Supply --aapt and --apksigner from Android SDK build-tools, or add them to PATH")
    result = inspect_apk(args.apk, args.aapt, args.apksigner)
    if args.notes:
        result["notes"] = args.notes.read_text(encoding="utf-8").strip()
        # Match JavaScript's string length used by the update service, including emoji.
        if len(result["notes"].encode("utf-16-le")) // 2 > 1000:
            parser.error("Release summary must contain at most 1,000 characters")
    args.output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Generated {args.output} for TesAir {result['versionName']} ({result['versionCode']})")


if __name__ == "__main__":
    main()
