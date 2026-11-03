"""Checks for catalog submissions using temporary files only."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("validator", Path(__file__).with_name("validate-module-market.py"))
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)


class CatalogTests(unittest.TestCase):
    def entry(self):
        entry = json.loads((validator.ROOT / "module-market/submission.example.json").read_text(encoding="utf-8"))
        entry["sha256"] = "a" * 64
        return entry

    def test_public_submission(self):
        validator.validate_entry(self.entry(), community=True)

    def test_missing_fields_fake_hash_credentials_and_self_dependency(self):
        for field, value in (
            ("sha256", "0" * 64), ("download", "https://example.com/module.jar?token=secret"),
            ("source", "http://example.com/repo"), ("download", "https://user:pass@example.com/file.jar"),
            ("coreVersions", []), ("depends", ["example-addon"]), ("nameEn", ""),
        ):
            with self.subTest(field=field, value=value):
                entry = self.entry()
                entry[field] = value
                with self.assertRaises(ValueError):
                    validator.validate_entry(entry, community=True)

    def test_duplicates_filenames_and_dependency_cycles(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            market = root / "module-market"
            community = market / "community"
            community.mkdir(parents=True)
            (market / "catalog.json").write_text("[]", encoding="utf-8")
            entry = self.entry()
            path = community / "example-addon.json"
            path.write_text(json.dumps(entry), encoding="utf-8")
            self.assertEqual({"official": 0, "community": 1}, validator.validate(root))
            wrong = community / "wrong-name.json"
            wrong.write_text(json.dumps(entry), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "Filename"):
                validator.validate(root)
            wrong.unlink()
            official = {key: entry[key] for key in ("id", "name", "nameEn", "description", "descriptionEn", "depends")}
            official.update(category="players", icon="package")
            (market / "catalog.json").write_text(json.dumps([official]), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "Duplicate"):
                validator.validate(root)
            (market / "catalog.json").write_text("[]", encoding="utf-8")
            other = copy.deepcopy(entry)
            other["id"] = "another"
            other["depends"] = ["example-addon"]
            (community / "another.json").write_text(json.dumps(other), encoding="utf-8")
            entry["depends"] = ["another"]
            path.write_text(json.dumps(entry), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "Circular"):
                validator.validate(root)
            path.unlink()
            with self.assertRaisesRegex(ValueError, "Unknown"):
                validator.validate(root)


if __name__ == "__main__":
    unittest.main()
