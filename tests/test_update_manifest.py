import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch


module_path = Path(__file__).resolve().parents[1] / "scripts/generate-update-manifest.py"
spec = importlib.util.spec_from_file_location("update_manifest", module_path)
manifest = importlib.util.module_from_spec(spec)
spec.loader.exec_module(manifest)


class UpdateManifestTest(unittest.TestCase):
    def generate(self, notes):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            note_file = folder / "notes.txt"
            output = folder / "update.json"
            note_file.write_text(notes, encoding="utf-8")
            arguments = ["generate-update-manifest.py", "fixture.apk", "--aapt", "aapt",
                         "--apksigner", "apksigner", "--notes", str(note_file), "--output", str(output)]
            metadata = {"versionName": "1.0.4", "versionCode": 66}
            with patch.object(manifest, "inspect_apk", return_value=metadata), patch("sys.argv", arguments), \
                    contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                try:
                    manifest.main()
                except SystemExit as error:
                    self.assertFalse(output.exists(), "Invalid notes must not produce a release manifest")
                    return error.code, None
                return 0, json.loads(output.read_text(encoding="utf-8"))

    def test_accepts_one_thousand_characters(self):
        status, generated = self.generate("a" * 1000)
        self.assertEqual(0, status)
        self.assertEqual("a" * 1000, generated["notes"])

    def test_rejects_one_thousand_and_one_characters(self):
        status, generated = self.generate("a" * 1001)
        self.assertEqual(2, status)
        self.assertIsNone(generated)

    def test_emoji_limit_matches_update_service_javascript_length(self):
        self.assertEqual(0, self.generate("🙂" * 500)[0])
        self.assertEqual(2, self.generate("🙂" * 500 + "a")[0])

    def test_rejects_empty_and_control_character_summaries(self):
        for notes in ("", " \n", "A change\x00", "A change\x1f"):
            self.assertEqual(2, self.generate(notes)[0])

    def test_release_changelog_prefers_whats_new_and_removes_markdown(self):
        body = "# TesAir v1.0.4\n\nIntro\n\n## What's new\n\n- **Simpler setup.**\n- [Updates](https://example.test).\n\n## Download\nAPK"
        self.assertEqual("- Simpler setup.\n- Updates.", manifest.release_notes(body))
        self.assertEqual("A clear improvement.", manifest.release_notes("# Title\n\nA clear improvement."))

    def test_derived_notes_are_bounded_without_splitting_emoji(self):
        notes = manifest.release_notes("🙂" * 700)
        self.assertEqual("🙂" * 499 + "…", notes)
        self.assertLessEqual(len(notes.encode("utf-16-le")) // 2, 1000)
        for body in (None, "", "# Title only", "Text\x00", "a" * (128 * 1024 + 1)):
            with self.assertRaises(ValueError):
                manifest.release_notes(body)

    def test_validation_failure_preserves_existing_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            notes = root / "notes.txt"
            notes.write_text("", encoding="utf-8")
            output = root / "update.json"
            output.write_text("existing valid manifest", encoding="utf-8")
            with patch.object(manifest, "inspect_apk", return_value={}), \
                    patch("sys.argv", ["generator", "fixture.apk", "--aapt", "aapt", "--apksigner", "apksigner",
                                       "--notes", str(notes), "--output", str(output)]), \
                    contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                manifest.main()
            self.assertEqual("existing valid manifest", output.read_text())


class SignedApkTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.apk = Path(self.directory.name) / "TesAir-v1.0.4.apk"
        self.apk.write_bytes(b"synthetic APK fixture")
        self.badging = "package: name='co.tesair.app' versionCode='66' versionName='1.0.4'"
        self.signed = f"Signer #1 certificate SHA-256 digest: {manifest.CERTIFICATE_SHA256}\n"

    def inspect(self, badging=None, signed=None):
        with patch.object(manifest.subprocess, "run", side_effect=[
            SimpleNamespace(stdout=self.badging if badging is None else badging),
            SimpleNamespace(stdout=self.signed if signed is None else signed),
        ]) as run:
            metadata = manifest.inspect_apk(self.apk, "aapt", "apksigner")
        self.assertEqual(["aapt", "dump", "badging", str(self.apk)], run.call_args_list[0].args[0])
        self.assertEqual(["apksigner", "verify", "--verbose", "--print-certs", str(self.apk)], run.call_args_list[1].args[0])
        return metadata

    def test_manifest_uses_verified_apk(self):
        metadata = self.inspect()
        self.assertEqual("1.0.4", metadata["versionName"])
        self.assertEqual(66, metadata["versionCode"])
        self.assertEqual(manifest.hashlib.sha256(self.apk.read_bytes()).hexdigest(), metadata["sha256"])

    def test_rejects_foreign_debug_unstable_or_invalid_version_apks(self):
        for badging in (self.badging.replace("co.tesair.app", "co.tesair.app.debug"),
                        self.badging.replace("1.0.4", "1.0.4-preview"), self.badging.replace("'66'", "'0'"),
                        self.badging.replace("'66'", "'2147483648'"), "invalid"):
            with self.subTest(badging=badging), self.assertRaises(ValueError):
                self.inspect(badging=badging)

    def test_rejects_unsigned_changed_or_multiple_signers(self):
        for signed in ("", self.signed.replace(manifest.CERTIFICATE_SHA256, "b" * 64),
                       self.signed + self.signed.replace("#1", "#2")):
            with self.assertRaises(ValueError):
                self.inspect(signed=signed)

    def test_rejects_empty_apk_before_running_tools(self):
        self.apk.write_bytes(b"")
        with patch.object(manifest.subprocess, "run") as run, self.assertRaises(ValueError):
            manifest.inspect_apk(self.apk, "aapt", "apksigner")
        run.assert_not_called()

    def test_release_asset_tag_digest_and_changelog_must_match(self):
        metadata = self.inspect()
        asset = {"name": self.apk.name, "browser_download_url": metadata["apkUrl"], "state": "uploaded",
                 "size": self.apk.stat().st_size, "digest": f"sha256:{metadata['sha256']}"}
        release = {"tag_name": "v1.0.4", "draft": False, "prerelease": False,
                   "body": "## What's new\n\nClearer setup.", "assets": [asset]}
        self.assertEqual("Clearer setup.", manifest.validate_release(release, metadata, self.apk))
        for change in ({"tag_name": "v1.0.5"}, {"draft": True}, {"prerelease": True},
                       {"assets": []}, {"assets": [asset, asset]}, {"body": ""}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                manifest.validate_release({**release, **change}, metadata, self.apk)
        for change in ({"digest": "sha256:" + "b" * 64}, {"size": 1}, {"state": "new"},
                       {"browser_download_url": "https://example.test/fixture.apk"}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                manifest.validate_release({**release, "assets": [{**asset, **change}]}, metadata, self.apk)


if __name__ == "__main__":
    unittest.main()
