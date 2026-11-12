"""Build release archives from verified outputs. Does not build or upload."""
from __future__ import annotations

import argparse
import importlib.util
import json
from pathlib import Path
import re
import shutil
import zipfile

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("alpha_packaging", ROOT / "package-alpha.py")
legacy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(legacy)
legacy.SOURCE_TREES += ("module-market", ".github")
legacy.ROOT_FILES += ("package-release.py", "README.en.md", "DEVELOPMENT.en.md", "CONTRIBUTING.md", "CONTRIBUTING.en.md")


def release_source_files():
    # Publish current guides; local handoffs and historical reports are not release inputs.
    return [path for path in legacy.source_files()
            if path.relative_to(ROOT).parts[0] != "docs"
            or path.relative_to(ROOT).as_posix() in ("docs/README.md", "docs/README.en.md")
            or path.relative_to(ROOT).as_posix().startswith(("docs/zh-CN/", "docs/en-US/"))]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-source", action="store_true")
    parser.add_argument("--list-source", action="store_true")
    args = parser.parse_args()
    match = re.findall(r"^lcVersion=(\d+\.\d+\.\d+(?:-[0-9A-Za-z][0-9A-Za-z.-]*)?)\s*$",
                       (ROOT / "gradle.properties").read_text(encoding="utf-8"), re.MULTILINE)
    if len(match) != 1:
        raise ValueError("Expected one release version in gradle.properties")
    version = match[0]
    paths = release_source_files()
    for language in ("zh-CN", "en-US"):
        for name in ("INSTALL", "MODULES", "USAGE", "FAQ", "RELEASE", "VALIDATION"):
            if ROOT / f"docs/{language}/{name}.md" not in paths:
                raise ValueError(f"Missing {language}/{name}.md")
    if args.list_source:
        print("\n".join(path.relative_to(ROOT).as_posix() for path in paths))
        return
    if args.check_source:
        print(json.dumps({"version": version, "source_files": len(paths)}))
        return

    core = ROOT / f"lc-core/build/libs/LunarCore-{version}.jar"
    legacy.validate_plugin(core, version, "LunarCore")
    with zipfile.ZipFile(core) as archive:
        archive.getinfo("web/index.html")
        archive.getinfo("META-INF/LICENSE")
        embedded = [n for n in archive.namelist() if n.startswith("builtin-modules/") and n.endswith(".jar")]
        if embedded != ["builtin-modules/lc-essentials.jar"]:
            raise ValueError(f"Core must contain only essentials: {embedded}")
        catalog_bytes = archive.read("module-market.json")
        catalog = json.loads(catalog_bytes)
    if catalog["version"] != version or len(catalog["modules"]) != 19:
        raise ValueError("Unexpected market version or module count")

    output = ROOT / "dist" / version
    output.mkdir(parents=True, exist_ok=True)
    assets = []

    def copy(path, name=None):
        target = output / (name or path.name)
        shutil.copyfile(path, target)
        assets.append(target)
        return target

    core_asset = copy(core)
    terrain_agent = copy(ROOT / "tools/terrain-agent/build/libs/LC-Terrain-Agent.jar")
    modules = []
    ids = set()
    for entry in catalog["modules"]:
        if entry["id"] in ids or entry["version"] != version:
            raise ValueError("Duplicate module or mismatched version")
        ids.add(entry["id"])
        built = ROOT / f"modules/lc-{entry['id']}/build/libs/{entry['asset']}"
        if built.stat().st_size != entry["size"] or legacy.digest(built) != entry["sha256"]:
            raise ValueError(f"Catalog checksum mismatch: {entry['id']}")
        with zipfile.ZipFile(built) as archive:
            archive.getinfo("META-INF/LICENSE")
            descriptor = archive.read("module.yml").decode("utf-8")
            if not re.search(r"^id:\s*" + re.escape(entry["id"]) + r"\s*$", descriptor, re.MULTILINE):
                raise ValueError("Module ID mismatch")
            if version not in descriptor:
                raise ValueError("Module version mismatch")
        modules.append(copy(built))

    catalog_asset = output / "module-market.json"
    catalog_asset.write_bytes(catalog_bytes)
    assets.append(catalog_asset)
    bridges = []
    for project, name in legacy.BRIDGES:
        directory = ROOT / f"integrations/{project}/build/libs"
        built = next((path for path in (directory / f"{name}.jar", directory / f"{project}.jar") if path.is_file()), None)
        if built is None:
            raise FileNotFoundError(f"Build integration first: {project}")
        legacy.validate_plugin(built, version, name)
        bridges.append(copy(built, f"{name}.jar"))

    # Community bridge: shipped separately from the official 19-entry catalog.
    structure_bridge = copy(ROOT / "modules/lc-structures-web/build/libs/lc-structures-web.jar")
    with zipfile.ZipFile(structure_bridge) as archive:
        archive.getinfo("module.yml")
        archive.getinfo("web/index.html")
        if any(name.startswith(("com/yungnickyoung/", "yungs/")) for name in archive.namelist()):
            raise ValueError("Structure bridge must not contain the generation engine")

    modules_zip = output / f"LunarCore-modules-{version}.zip"
    with zipfile.ZipFile(modules_zip, "w") as archive:
        for path in [*modules, structure_bridge]:
            legacy.add_file(archive, path, path.name)
        legacy.add_file(archive, ROOT / "docs/zh-CN/MODULES.md", "MODULES.zh-CN.md")
        legacy.add_file(archive, ROOT / "docs/en-US/MODULES.md", "MODULES.en-US.md")
        legacy.add_file(archive, terrain_agent, "terrain-setup/" + terrain_agent.name)
        legacy.add_file(archive, ROOT / "modules/lc-terrain/README.md", "terrain-setup/README.zh-CN.md")
        legacy.add_file(archive, ROOT / "modules/lc-terrain/README.en.md", "terrain-setup/README.en.md")
    assets.append(modules_zip)
    source = output / f"LunarCore-{version}-source.zip"
    with zipfile.ZipFile(source, "w") as archive:
        for path in paths:
            legacy.add_file(archive, path, "LunarCore/" + path.relative_to(ROOT).as_posix())
        manifest = {p.relative_to(ROOT).as_posix(): legacy.digest(p) for p in paths}
        legacy.add_bytes(archive, "SOURCE-SHA256.json", (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
    assets.append(source)
    bundle = output / f"LunarCore-{version}.zip"
    with zipfile.ZipFile(bundle, "w") as archive:
        for path in (core_asset, source, modules_zip, catalog_asset, terrain_agent, *bridges):
            legacy.add_file(archive, path, path.name)
        for path in [*modules, structure_bridge]:
            legacy.add_file(archive, path, "modules/" + path.name)
        for name in ("LICENSE", "THIRD-PARTY-NOTICES.md", "README.md", "README.en.md"):
            legacy.add_file(archive, ROOT / name, name)
        legacy.add_file(archive, ROOT / "modules/lc-terrain/README.md", "terrain-setup/README.zh-CN.md")
        legacy.add_file(archive, ROOT / "modules/lc-terrain/README.en.md", "terrain-setup/README.en.md")
        for language in ("zh-CN", "en-US"):
            for path in sorted((ROOT / "docs" / language).glob("*.md")):
                legacy.add_file(archive, path, f"docs/{language}/{path.name}")
        legacy.add_bytes(archive, "INSTALL.txt", (
            f"LunarCore 琉月 v{version}\n"
            "Java 25 / Paper 26.3\n"
            "核心 JAR 放入 plugins/。附加模块按需放入 plugins/LunarCore/modules/。\n"
            "结构美化：另行下载 LunarCoreStructures.jar 放入 plugins/；lc-structures-web.jar 放入 LunarCore/modules/。\n"
            "地形模块另需启动参数 -javaagent:LC-Terrain-Agent.jar，见 terrain-setup/。\n"
            "停服后安装或更新，保留数据目录。详见 docs/zh-CN/INSTALL.md。\n"
            "Core JAR: plugins/. Optional modules: plugins/LunarCore/modules/.\n"
            "Structures: download LunarCoreStructures.jar separately into plugins/; install lc-structures-web.jar into LunarCore/modules/.\n"
            "Terrain also requires -javaagent:LC-Terrain-Agent.jar; see terrain-setup/.\n"
            "Stop before installing or updating. Keep the data folder. See docs/en-US/INSTALL.md.\n"
        ).encode("utf-8"))
    assets.append(bundle)
    for path in (source, modules_zip, bundle):
        with zipfile.ZipFile(path) as archive:
            if archive.testzip() is not None:
                raise ValueError(f"Invalid ZIP: {path.name}")
    checksums = {p.name: legacy.digest(p) for p in assets}
    (output / "SHA256SUMS.txt").write_text(
        "".join(f"{value}  {name}\n" for name, value in sorted(checksums.items())), encoding="utf-8")
    print(json.dumps({"version": version, "source_files": len(paths), "assets": len(assets) + 1,
                      "output": str(output), "sha256": checksums}, indent=2))


if __name__ == "__main__":
    main()
