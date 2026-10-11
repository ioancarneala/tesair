#!/usr/bin/env python3
"""Check a source-publication candidate for common private-file mistakes.

This checks the working tree, including untracked and ignored files, so it also
works on an export before git init. Ordinary generated/cache directories are
skipped; any tracked files beneath them are still checked. This is a conservative
release guard, not a complete secret scanner or a review of Git history/licensing.
"""

import argparse
import os
from pathlib import Path
import re
import stat
import subprocess
import sys


GENERATED_DIRECTORIES = {
    ".git", ".gradle", ".kotlin", ".cxx", ".externalNativeBuild",
    ".android-sdk", "build", "__pycache__", ".pytest_cache", "node_modules",
}
PRIVATE_DIRECTORIES = {
    ".private", ".build-tools", ".aws", ".ssh", ".secrets", "offline-mfi", "captures",
    "device-reports", "diagnostic-reports", "bugreports", "logcat",
}
PRIVATE_FILES = {
    "local.properties", "release-signing.properties", "keystore.properties", "signing.json",
    "google-services.json", "service-account.json", "credentials.json",
    "secrets.json", "certificates.json", "bundle.json",
}
FORBIDDEN_SUFFIXES = {
    ".pk8", ".p7b", ".pem", ".key", ".p12", ".pfx", ".jks",
    ".keystore", ".crt", ".cer", ".der", ".apk", ".aab", ".apks",
    ".zip", ".tar", ".gz", ".tgz", ".bz2", ".xz", ".7z", ".rar",
    ".jar", ".aar", ".dex", ".class", ".so", ".o", ".a", ".dylib",
    ".log", ".logcat", ".har", ".pcap", ".pcapng",
}
ALLOWED_ARCHIVES = {"gradle/wrapper/gradle-wrapper.jar"}
CONTENT_RULES = (
    ("private key material", re.compile(
        rb"-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----(?:\s|\\[rn])+[A-Za-z0-9+/=\r\n]{40,}"
    )),
    ("GitHub credential", re.compile(rb"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})\b")),
    ("AWS access key", re.compile(rb"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b")),
    ("Google API key", re.compile(rb"\bAIza[A-Za-z0-9_-]{35}\b")),
    ("Slack credential", re.compile(rb"\bxox[baprs]-[A-Za-z0-9-]{20,}\b")),
    ("configured private build input", re.compile(
        rb"(?im)^\s*(?:export\s+)?(?:BROWSER_CERTIFICATE_DOWNLOAD_TOKEN|CLOUDFLARE_API_TOKEN|"
        rb"CF_DNS_API_TOKEN|CF_KV_API_TOKEN|ANDROID_KEYSTORE_PASSWORD|ANDROID_KEY_PASSWORD)"
        rb"\s*[:=]\s*[\"']?[A-Za-z0-9_+/=-]{24,}"
    )),
)


def path_category(relative):
    """Reject private containers without reading their contents."""
    parts = tuple(part.lower() for part in relative.parts)
    name = parts[-1]
    if any(part in PRIVATE_DIRECTORIES or part.startswith((".private-", ".private_"))
           for part in parts):
        return "private directory"
    if (name == ".env" or name.endswith(".env") or name.startswith(".env.")) and name != ".env.example":
        return "local environment configuration"
    if name in PRIVATE_FILES:
        return "local/private configuration"
    if relative.suffix.lower() in FORBIDDEN_SUFFIXES and relative.as_posix() not in ALLOWED_ARCHIVES:
        return "credential, archive, binary output, or device report"
    if re.match(r"(?:bugreport|diagnostic-report|device-report)[-_].*", name):
        return "device report"
    return None


def tracked_paths(root):
    """Do not let a generated-directory exclusion hide an indexed file."""
    try:
        result = subprocess.run(
            ["git", "ls-files", "--cached", "-z", "--", "."], cwd=root,
            capture_output=True, check=False,
        )
    except FileNotFoundError:
        return []
    if result.returncode != 0:
        return []  # Plain source exports need no Git installation or repository.
    return [Path(os.fsdecode(name)) for name in result.stdout.split(b"\0") if name]


def check_tree(root):
    root = Path(root)
    findings = set()
    candidates = set()

    def record(relative, category):
        findings.add((relative.as_posix(), category))

    def walk(directory):
        try:
            entries = sorted(directory.iterdir())
        except OSError:
            record(directory.relative_to(root), "unreadable directory")
            return
        for entry in entries:
            relative = entry.relative_to(root)
            try:
                mode = entry.lstat().st_mode
            except OSError:
                record(relative, "unreadable entry")
                continue
            if stat.S_ISLNK(mode):
                record(relative, "symbolic link")
                continue
            category = path_category(relative)
            if category:
                record(relative, category)
                continue
            if stat.S_ISDIR(mode):
                if entry.name not in GENERATED_DIRECTORIES:
                    walk(entry)
            elif stat.S_ISREG(mode):
                candidates.add(relative)
            else:
                record(relative, "non-regular file")

    if not root.is_dir() or root.is_symlink():
        return 0, [(".", "source root must be a real directory")]
    walk(root)
    for relative in tracked_paths(root):
        if relative.is_absolute() or ".." in relative.parts:
            record(Path("."), "tracked path outside source root")
            continue
        # Never follow a tracked path through a symlink, even under a skipped cache.
        if any(root.joinpath(*relative.parts[:index]).is_symlink()
               for index in range(1, len(relative.parts) + 1)):
            record(relative, "symbolic link")
            continue
        category = path_category(relative)
        if category:
            record(relative, category)
        elif (root / relative).is_file():
            candidates.add(relative)
        elif not (root / relative).exists():
            record(relative, "tracked file missing from working tree")

    for relative in sorted(candidates):
        try:
            content = (root / relative).read_bytes()
        except OSError:
            record(relative, "unreadable file")
            continue
        for category, pattern in CONTENT_RULES:
            if pattern.search(content):
                record(relative, category)
    if not candidates:
        record(Path("."), "empty source candidate")
    return len(candidates), sorted(findings)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1],
                        help="Source candidate directory (default: this repository)")
    args = parser.parse_args()
    count, findings = check_tree(args.root.absolute())
    if findings:
        print("Public source guard failed (paths and categories only):", file=sys.stderr)
        for path, category in findings:
            print(f"  {path}: {category}", file=sys.stderr)
        return 1
    print(f"Public source guard passed: checked {count} files, including untracked files.")
    print("Generated caches are excluded unless tracked; Git history and licensing need separate review.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
