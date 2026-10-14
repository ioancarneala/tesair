import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
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


if __name__ == "__main__":
    unittest.main()
