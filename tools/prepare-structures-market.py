"""Create the community entry from a built bridge and its published download; verify identical bytes."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "modules/lbr-structures-web/build/libs/lbr-structures-web.jar")
    parser.add_argument("--download", required=True)
    parser.add_argument("--source", required=True)
    core_version = re.search(r"^lbrVersion=(\S+)$",
                             (ROOT / "gradle.properties").read_text(encoding="utf-8"), re.MULTILINE).group(1)
    parser.add_argument("--core-version", default=core_version)
    parser.add_argument("--output", type=Path, default=ROOT / "module-market/community/structures.json")
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location("market", ROOT / "tools/validate-module-market.py")
    market = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(market)
    market.public_url(args.download)
    market.public_url(args.source)
    content = args.jar.read_bytes()
    digest = hashlib.sha256(content).hexdigest()
    with zipfile.ZipFile(args.jar) as archive:
        descriptor = archive.read("module.yml").decode()
        if not re.search(r"^id: structures$", descriptor, re.MULTILINE):
            raise ValueError("Not the structures bridge module")
        version = re.search(r"^version: ['\"]?([^'\"\s]+)", descriptor, re.MULTILINE).group(1)
        archive.getinfo("web/index.html")
        if any(name.startswith(("com/yungnickyoung/", "yungs/")) for name in archive.namelist()):
            raise ValueError("Bridge must not bundle the generation engine")
    request = urllib.request.Request(args.download, headers={"User-Agent": "LunarCore-market-validator"})
    with urllib.request.urlopen(request, timeout=60) as response:
        published = response.read(len(content) + 1)
    if hashlib.sha256(published).hexdigest() != digest:
        raise ValueError("Published download differs from the built bridge; do not submit an incorrect checksum")
    entry = {
        "id": "structures", "name": "结构美化管理", "nameEn": "Structure upgrades management",
        "description": "LunarCoreStructures 的 Web 管理桥接。需另装管理 API v1 的生成插件到 plugins/，核心须含 NativeGeneratorDelegate API；本模块放入 LunarCore/modules/。",
        "descriptionEn": "Web bridge for LunarCoreStructures. Install the API-v1 generation plugin separately in plugins/ and use a core exposing NativeGeneratorDelegate; install this module in LunarCore/modules/.",
        "version": version, "coreVersions": [args.core_version], "author": "rtiags",
        "source": args.source, "download": args.download, "license": "GPL-3.0-only",
        "sha256": digest, "depends": [],
    }
    market.validate_entry(entry, community=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(entry, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Verified download and wrote {args.output}")


if __name__ == "__main__":
    main()
