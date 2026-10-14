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
REPOSITORY = "ioancarneala/tesair"
CERTIFICATE_SHA256 = "a98ff9a4a01196ed752a756aabf38d29e495f5c8754b0a4c26b844b839a3612b"
MAX_APK_BYTES = 128 * 1024 * 1024


def validate_notes(notes: str) -> str:
    if not notes.strip() or re.search(r"[\x00-\x08\x0b\x0c\x0e-\x1f]", notes):
        raise ValueError("Release summary must be nonempty text without control characters")
    notes = notes.strip()
    if len(notes.encode("utf-16-le")) // 2 > 1000:
        raise ValueError("Release summary must contain at most 1,000 characters")
    return notes


def release_notes(body: str) -> str:
    if not isinstance(body, str) or len(body) > 128 * 1024:
        raise ValueError("Expected a bounded release changelog")
    if re.search(r"[\x00-\x08\x0b\x0c\x0e-\x1f]", body):
        raise ValueError("Release changelog contains control characters")
    section = re.search(r"^##\s+(?:what['’]s new|changes|changelog)\s*$([\s\S]*?)(?=^##\s|\Z)",
                        body, re.I | re.M)
    text = section.group(1) if section else body
    text = re.sub(r"^#{1,6}\s+.*$", "", text, flags=re.M)
    text = re.sub(r"!?\[([^\]]+)\]\([^)]*\)", r"\1", text)
    text = text.replace("**", "").replace("`", "").strip()
    if len(text.encode("utf-16-le")) // 2 > 1000:
        # Count UTF-16 units as the Worker does, without splitting an emoji.
        units = 0
        end = 0
        for character in text:
            units += len(character.encode("utf-16-le")) // 2
            if units > 999:
                break
            end += 1
        text = text[:end].rstrip() + "…"
    return validate_notes(text)


def validate_release(release: dict, metadata: dict, apk: Path) -> str:
    tag = f"v{metadata['versionName']}"
    if (not isinstance(release, dict) or release.get("tag_name") != tag
            or release.get("draft") is not False or release.get("prerelease") is not False):
        raise ValueError("Expected the matching published stable release")
    assets = release.get("assets")
    expected_name = f"TesAir-{tag}.apk"
    if not isinstance(assets, list):
        raise ValueError("Release assets are missing")
    matches = [asset for asset in assets if isinstance(asset, dict) and asset.get("name") == expected_name]
    if len(matches) != 1:
        raise ValueError("Expected exactly one canonical release APK")
    asset = matches[0]
    if (apk.name != expected_name or asset.get("browser_download_url") != metadata["apkUrl"]
            or asset.get("state") != "uploaded" or asset.get("size") != apk.stat().st_size):
        raise ValueError("Downloaded APK does not match the release asset")
    # GitHub exposes this digest for newly uploaded assets; older assets may omit it.
    if asset.get("digest") not in (None, f"sha256:{metadata['sha256']}"):
        raise ValueError("Downloaded APK does not match GitHub's asset digest")
    return release_notes(release.get("body"))


def inspect_apk(apk: Path, aapt: str, apksigner: str) -> dict:
    if not apk.is_file() or not 0 < apk.stat().st_size <= MAX_APK_BYTES:
        raise ValueError("Expected a nonempty APK no larger than 128 MiB")
    badging = subprocess.run([aapt, "dump", "badging", str(apk)], check=True,
                             capture_output=True, text=True).stdout
    package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging, re.M)
    if package is None or package.group(1) != PACKAGE:
        raise ValueError("Expected the stable co.tesair.app APK")
    version = package.group(3)
    if re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version) is None or len(version) > 30:
        raise ValueError("A stable release version is required")
    if not 0 < int(package.group(2)) <= 2_147_483_647:
        raise ValueError("A valid Android version code is required")
    signed = subprocess.run([apksigner, "verify", "--verbose", "--print-certs", str(apk)], check=True,
                            capture_output=True, text=True).stdout
    certificates = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest: ([a-fA-F0-9]{64})$", signed, re.M)
    if len(certificates) != 1:
        raise ValueError("Expected exactly one verified APK signing identity")
    if certificates[0].lower() != CERTIFICATE_SHA256:
        raise ValueError("APK does not use the established TesAir signing identity")
    with apk.open("rb") as stream:
        checksum = hashlib.file_digest(stream, "sha256").hexdigest()
    return {
        "schemaVersion": 1,
        "packageName": PACKAGE,
        "versionCode": int(package.group(2)),
        "versionName": version,
        "apkUrl": f"https://github.com/{REPOSITORY}/releases/download/v{version}/TesAir-v{version}.apk",
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
    changelog = parser.add_mutually_exclusive_group(required=True)
    changelog.add_argument("--notes", type=Path, help="Nonempty plain-text release summary, at most 1,000 characters")
    changelog.add_argument("--release-json", type=Path, help="GitHub REST release JSON to validate and derive its changelog")
    args = parser.parse_args()
    if not args.aapt or not args.apksigner:
        parser.error("Supply --aapt and --apksigner from Android SDK build-tools, or add them to PATH")
    try:
        result = inspect_apk(args.apk, args.aapt, args.apksigner)
        if args.notes:
            result["notes"] = validate_notes(args.notes.read_text(encoding="utf-8"))
        else:
            release = json.loads(args.release_json.read_text(encoding="utf-8"))
            result["notes"] = validate_release(release, result, args.apk)
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        # Tool stdout/stderr can include APK contents; only show our own validation messages.
        parser.error(str(error) if isinstance(error, ValueError) else "Could not inspect the APK or release inputs")
    args.output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Generated {args.output} for TesAir {result['versionName']} ({result['versionCode']})")


if __name__ == "__main__":
    main()
