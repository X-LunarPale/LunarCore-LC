"""Validate official and community catalog entries without downloading or executing modules."""
from __future__ import annotations

import json
from pathlib import Path
import re
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
ID = re.compile(r"[a-z0-9][a-z0-9_-]{0,63}")
TEXT_FIELDS = ("name", "nameEn", "description", "descriptionEn")
COMMUNITY_FIELDS = ("id", *TEXT_FIELDS, "version", "coreVersions", "author",
                    "source", "download", "license", "sha256", "depends")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def public_url(value):
    require(isinstance(value, str) and len(value) <= 2048, "URL must be text")
    parsed = urlsplit(value)
    require(parsed.scheme == "https" and parsed.hostname and not parsed.username
            and not parsed.password and not parsed.query and not parsed.fragment,
            "Use a public HTTPS URL without credentials, query or fragment")
    require(parsed.hostname not in {"localhost", "127.0.0.1", "::1"}, "URL must be public")


def validate_entry(entry, community=False):
    require(isinstance(entry, dict), "Entry must be an object")
    require(isinstance(entry.get("id"), str) and ID.fullmatch(entry["id"]), "Invalid module ID")
    require(entry["id"] != "essentials", "The core module cannot be replaced")
    for field in TEXT_FIELDS:
        require(isinstance(entry.get(field), str) and 0 < len(entry[field].strip()) <= 1000,
                f"Missing or oversized {field}")
    dependencies = entry.get("depends")
    require(isinstance(dependencies, list) and all(isinstance(dep, str) and ID.fullmatch(dep)
                                                 for dep in dependencies), "Invalid depends")
    require(len(set(dependencies)) == len(dependencies) and entry["id"] not in dependencies,
            "Duplicate or self dependency")
    if community:
        require(set(entry) == set(COMMUNITY_FIELDS), "Community fields must match the submission template")
        for field in ("version", "author", "license"):
            require(isinstance(entry.get(field), str) and 0 < len(entry[field].strip()) <= 120,
                    f"Missing or oversized {field}")
        versions = entry.get("coreVersions")
        require(isinstance(versions, list) and versions and all(
            isinstance(version, str) and re.fullmatch(r"\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?", version)
            for version in versions), "List tested core versions")
        require(isinstance(entry.get("sha256"), str) and re.fullmatch(r"[a-fA-F0-9]{64}", entry["sha256"])
                and entry["sha256"] != "0" * 64, "Provide the actual file SHA-256")
        public_url(entry.get("source"))
        public_url(entry.get("download"))
    else:
        require(entry.get("category") in {"survival", "players", "security", "server"}, "Invalid category")
        require(isinstance(entry.get("icon"), str) and entry["icon"], "Missing icon")


def validate(root=ROOT):
    market = root / "module-market"
    official = json.loads((market / "catalog.json").read_text(encoding="utf-8"))
    require(isinstance(official, list), "Official catalog must be a list")
    entries = {}
    for entry in official:
        validate_entry(entry)
        require(entry["id"] not in entries, f"Duplicate ID: {entry['id']}")
        entries[entry["id"]] = entry
    community = sorted((market / "community").glob("*.json"))
    for path in community:
        entry = json.loads(path.read_text(encoding="utf-8"))
        validate_entry(entry, community=True)
        require(path.stem == entry["id"], f"Filename must match ID: {path.name}")
        require(entry["id"] not in entries, f"Duplicate ID: {entry['id']}")
        entries[entry["id"]] = entry

    visiting, done = set(), {"essentials"}

    def visit(module):
        if module in done:
            return
        require(module in entries, f"Unknown dependency: {module}")
        require(module not in visiting, f"Circular dependency: {module}")
        visiting.add(module)
        for dependency in entries[module]["depends"]:
            visit(dependency)
        visiting.remove(module)
        done.add(module)

    for module in entries:
        visit(module)
    return {"official": len(official), "community": len(community)}


if __name__ == "__main__":
    try:
        print(json.dumps(validate()))
    except (ValueError, OSError, KeyError) as error:
        raise SystemExit(f"Module catalog: {error}") from error
