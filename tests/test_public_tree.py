"""Publication-guard regression tests using only temporary, synthetic fixtures."""

import importlib.util
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "check_public_tree.py"
SPEC = importlib.util.spec_from_file_location("public_tree", SCRIPT)
guard = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(guard)


class PublicTreeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def write(self, name, content="public source\n"):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        return path

    def check(self):
        return guard.check_tree(self.root)

    def test_plain_export_checks_untracked_source_without_git(self):
        self.write("README.md")
        self.write("shared/src/main/cpp/native.c", "int main(void) { return 0; }\n")
        self.write("LICENSE", "Public license notice\n")
        self.write("gradle/wrapper/gradle-wrapper.jar", "synthetic wrapper fixture")
        count, findings = self.check()
        self.assertEqual(count, 4)
        self.assertEqual(findings, [])

    def test_empty_export_fails(self):
        count, findings = self.check()
        self.assertEqual(count, 0)
        self.assertIn((".", "empty source candidate"), findings)

    def test_only_generated_output_is_not_a_source_candidate(self):
        self.write("build/generated/source.kt")
        self.assertIn((".", "empty source candidate"), self.check()[1])

    def test_untracked_private_files_are_rejected_by_path(self):
        self.write("README.md")
        for name in ("identity.pk8", "archive.zip", ".env", ".env.production",
                     "local.properties", "bundle.json", "device-report-123.json",
                     "capture.pcap", "app.apk", "other.jar"):
            with self.subTest(name=name):
                path = self.write(name)
                self.assertTrue(any(item[0] == name for item in self.check()[1]))
                path.unlink()

    def test_ignored_private_directory_is_not_opened(self):
        self.write("README.md")
        self.write(".gitignore", ".private/\n")
        self.write(".private/identity.pk8", "private-container-fixture")
        original_read = Path.read_bytes

        def read_public_only(path):
            self.assertNotIn(".private", path.parts)
            return original_read(path)

        with patch.object(Path, "read_bytes", read_public_only):
            self.assertIn((".private", "private directory"), self.check()[1])

    def test_signing_configuration_is_rejected_without_reading_contents(self):
        self.write("README.md")
        self.write(".gitignore", "signing.json\n")
        self.write("signing.json", '{"password": "synthetic-fixture"}\n')
        original_read = Path.read_bytes

        def read_public_only(path):
            self.assertNotEqual(path.name, "signing.json")
            return original_read(path)

        with patch.object(Path, "read_bytes", read_public_only):
            self.assertIn(("signing.json", "local/private configuration"), self.check()[1])

    def test_generated_build_artifacts_do_not_break_post_build_check(self):
        self.write("README.md")
        self.write("mobile/build/outputs/apk/debug/app-debug.apk")
        self.write(".gradle/caches/generated.zip")
        self.write("shared/.cxx/debug/native.o")
        self.assertEqual(self.check()[1], [])

    def test_private_working_directories_are_rejected_without_reading_contents(self):
        self.write("README.md")
        names = (".private-test", ".private-tesair-release", ".private_backup", ".build-tools")
        for name in names:
            self.write(name + "/fixture.txt", "must not be read")
        original_read = Path.read_bytes

        def read_public_only(path):
            self.assertFalse(any(name in path.parts for name in names))
            return original_read(path)

        with patch.object(Path, "read_bytes", read_public_only):
            findings = self.check()[1]
        for name in names:
            self.assertIn((name, "private directory"), findings)

    @unittest.skipUnless(shutil.which("git"), "Git is needed for index regression cases")
    def test_tracked_file_in_ignored_build_directory_is_checked(self):
        self.write("README.md")
        self.write(".gitignore", "build/\n")
        self.write("build/release.key", "synthetic fixture")
        subprocess.run(["git", "init", "--quiet"], cwd=self.root, check=True)
        subprocess.run(["git", "add", "-f", "build/release.key"], cwd=self.root, check=True)
        self.assertIn(("build/release.key", "credential, archive, binary output, or device report"),
                      self.check()[1])

    @unittest.skipUnless(shutil.which("git"), "Git is needed for index regression cases")
    def test_tracked_content_in_ignored_build_directory_is_scanned(self):
        self.write("README.md")
        self.write(".gitignore", "build/\n")
        self.write("build/config.txt", "ghp_" + "A" * 36)
        subprocess.run(["git", "init", "--quiet"], cwd=self.root, check=True)
        subprocess.run(["git", "add", "-f", "build/config.txt"], cwd=self.root, check=True)
        self.assertIn(("build/config.txt", "GitHub credential"), self.check()[1])

    def test_file_and_directory_symlinks_are_rejected(self):
        target = self.write("source.txt")
        (self.root / "linked.txt").symlink_to(target)
        (self.root / "build").symlink_to(self.root, target_is_directory=True)
        findings = self.check()[1]
        self.assertIn(("linked.txt", "symbolic link"), findings)
        self.assertIn(("build", "symbolic link"), findings)

    def test_symlink_source_root_is_rejected(self):
        source = self.root / "source"
        source.mkdir()
        alias = self.root / "alias"
        alias.symlink_to(source, target_is_directory=True)
        self.assertEqual(guard.check_tree(alias)[1],
                         [(".", "source root must be a real directory")])

    def test_private_key_contents_are_found_without_forbidding_parser_code(self):
        self.write("parser.java", 'String begin = "-----BEGIN PRIVATE KEY-----";\n')
        self.assertEqual(self.check()[1], [])
        self.write("accidental.txt", "-----BEGIN " + "PRIVATE KEY-----\n" + "A" * 64)
        self.assertIn(("accidental.txt", "private key material"), self.check()[1])

    def test_environment_example_is_allowed_but_real_configured_token_is_not(self):
        self.write(".env.example", "BROWSER_CERTIFICATE_DOWNLOAD_TOKEN=\n")
        self.assertEqual(self.check()[1], [])
        self.write("config.txt", "BROWSER_CERTIFICATE_DOWNLOAD_TOKEN=" + "A" * 48)
        self.assertIn(("config.txt", "configured private build input"), self.check()[1])

    def test_cli_never_prints_matched_values(self):
        secret = "ghp_" + "A" * 36
        self.write("config.txt", secret)
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "--root", str(self.root)],
            capture_output=True, text=True,
        )
        self.assertEqual(result.returncode, 1)
        self.assertIn("config.txt: GitHub credential", result.stderr)
        self.assertNotIn(secret, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
