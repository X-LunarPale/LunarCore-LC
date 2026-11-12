"""Package the current alphaN release after the lead builds and verifies it.

Inspection only: python package-alpha.py --list-source / --check-source
Packaging:       python package-alpha.py
Only explicitly selected source trees are visited; runtime trees are never copied.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import zipfile

ROOT = Path(__file__).resolve().parent
SOURCE_TREES = (
    "lc-api", "lc-core", "modules", "integrations", "examples", "web-ui", "docs",
    "gradle", "third-party/lagfixer", "tools", "resource-packs",
)
ROOT_FILES = (
    "settings.gradle.kts", "build.gradle.kts", "gradle.properties",
    "gradlew", "gradlew.bat", "gw.sh", "build.sh", "package-alpha.py",
    "LICENSE", "THIRD-PARTY-NOTICES.md", "README.md",
    "DEVELOPMENT.md", ".gitignore", ".gitattributes",
)
EXCLUDED_DIRS = {
    "build", "build-bridge-tests", "node_modules", "dist", ".git", ".gradle",
    ".idea", ".vscode", ".dsh", "__pycache__", ".pytest_cache", ".vite",
    "coverage", "logs", "artifacts", "plugins", "world_nether", "world_the_end",
}
EXCLUDED_SUFFIXES = {
    ".class", ".jar", ".db", ".sqlite", ".sqlite3", ".log", ".pyc",
    ".zip", ".exe", ".dll", ".so", ".dylib", ".bin", ".lock", ".tmp",
    ".pem", ".key", ".p12", ".pfx", ".jks", ".keystore", ".sha256",
}
EXCLUDED_FILES = {
    "tools/deploy-test-integrations.py", "tools/start-test-server.ps1",
    "tools/test-server-control.py", "tools/remote-recover-lagfix.py",
    "tools/remote-sftp-deploy.py", "tools/local-acceptance-auth.py",
    "resource-packs/lunar-island/NotoSansSC.ttf",
    "resource-packs/lunar-island/pack-preview.png",
    "resource-packs/lunar-island/build-manifest.json",
}
SOURCE_BINARIES = {
    "gradle/wrapper/gradle-wrapper.jar",
    "third-party/lagfixer/upstream/gradle/wrapper/gradle-wrapper.jar",
}
REQUIRED = (
    "gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar",
    "gradle/wrapper/gradle-wrapper.properties", "lc-core/build.gradle.kts",
    "web-ui/package.json", "web-ui/package-lock.json", "web-ui/vite.config.js",
    "web-ui/index.html", "web-ui/tests/api-security.test.js",
    "web-ui/tests/panel.test.js", "modules/lc-qqauth/build.gradle.kts",
    "modules/lc-vote/src/main/java/com/lunarboreal/vote/VoteModule.java", "third-party/lagfixer/upstream/LICENSE",
    "modules/lc-world/src/main/java/com/lunarboreal/world/WorldModule.java",
    "modules/lc-world/src/main/java/com/lunarboreal/world/WorldWeb.java",
    "integrations/lc-multiverse-web/build.gradle.kts",
    "integrations/lc-multiverse-web/src/main/resources/plugin.yml",
    "integrations/lc-playertitle-web/build.gradle.kts",
    "integrations/lc-playertitle-web/src/main/resources/plugin.yml",
    "tools/lagfixer-probe/src/main/java/com/lunarboreal/probe/LagFixerProbe.java",
    "tools/lagfixer-probe/src/main/resources/plugin.yml",
    "tools/terrain-native-probe/offline/TerrainRegionRead.java",
    "tools/verify-ai-security-ui.py", "tools/verify-terrain-ui.mjs",
    "resource-packs/lunar-island/OFL.txt",
    "resource-packs/lunar-island/pack/LICENSES/NotoSansSC-OFL.txt",
    "resource-packs/lunar-island/pack/pack.mcmeta",
    "resource-packs/lunar-island/pack/assets/lunar/font/mono.json",
)
BRIDGES = (
    ("lc-multiverse-web", "LcMultiverseWeb"),
    ("lc-playertitle-web", "LcPlayerTitleWeb"),
)


def version() -> str:
    text = (ROOT / "gradle.properties").read_text(encoding="utf-8")
    found = re.findall(r"^lcVersion=(alpha[1-9][0-9]*)\s*$", text, re.MULTILINE)
    if len(found) != 1:
        raise ValueError("gradle.properties must contain exactly one lcVersion=alphaN")
    return found[0]


def allowed(path: Path) -> bool:
    rel = path.relative_to(ROOT)
    name = rel.as_posix()
    if path.is_symlink() or any(part.startswith(".env") for part in rel.parts):
        return False
    if name in EXCLUDED_FILES or any(part.startswith((".grim-", "zip-probe")) for part in rel.parts):
        return False
    if any(part in EXCLUDED_DIRS or part.startswith(("run-", "dist-")) for part in rel.parts):
        return False
    lower = path.name.lower()
    if lower in {"server.properties", "eula.txt", "state.properties", "import-bindings.yml"}:
        return False
    if any(word in lower for word in ("web-admin-password", "credential", "private-key")):
        return False
    if name.startswith("integrations/lc-playertitle-web/inspect-") and lower.endswith(".sh"):
        return False
    # The checked-in Gradle wrapper is required to rebuild from source.
    if name in SOURCE_BINARIES:
        return True
    return path.suffix.lower() not in EXCLUDED_SUFFIXES


def source_files() -> list[Path]:
    selected = set()
    for name in ROOT_FILES:
        path = ROOT / name
        if not path.is_file() or path.is_symlink():
            raise FileNotFoundError(f"Missing regular source file: {name}")
        selected.add(path)
    for name in SOURCE_TREES:
        base = ROOT / name
        if not base.is_dir() or base.is_symlink():
            raise FileNotFoundError(f"Missing regular source tree: {name}")
        for directory, dirs, files in os.walk(base, followlinks=False):
            parent = Path(directory)
            dirs[:] = sorted(d for d in dirs if d not in EXCLUDED_DIRS
                             and not d.startswith(("run-", "dist-", ".env"))
                             and not (parent / d).is_symlink())
            for filename in files:
                path = parent / filename
                if path.is_file() and allowed(path):
                    selected.add(path)
    paths = sorted(selected, key=lambda p: p.relative_to(ROOT).as_posix())
    included = {p.relative_to(ROOT).as_posix() for p in paths}
    for name in REQUIRED:
        if name not in included:
            raise FileNotFoundError(f"Required build input excluded or missing: {name}")
    # No expected build output or mutable runtime database may slip into the archive.
    assert all(allowed(p) for p in paths)
    return paths


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def add_bytes(archive: zipfile.ZipFile, name: str, data: bytes, executable: bool = False) -> None:
    entry = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
    entry.create_system = 3
    entry.external_attr = (0o100755 if executable else 0o100644) << 16
    entry.compress_type = zipfile.ZIP_DEFLATED
    archive.writestr(entry, data)


def add_file(archive: zipfile.ZipFile, path: Path, name: str) -> None:
    add_bytes(archive, name, path.read_bytes(), path.name in {"gradlew", "gw.sh", "build.sh"})


def validate_plugin(path: Path, release: str, plugin_name: str) -> None:
    with zipfile.ZipFile(path) as archive:
        descriptor = "paper-plugin.yml" if "paper-plugin.yml" in archive.namelist() else "plugin.yml"
        plugin = archive.read(descriptor).decode("utf-8")
        if not re.search(r"^name:\s*['\"]?" + re.escape(plugin_name) + r"['\"]?\s*$", plugin, re.MULTILINE):
            raise ValueError(f"Unexpected plugin name in {path.relative_to(ROOT)}")
        if not re.search(r"^version:\s*['\"]?" + re.escape(release) + r"['\"]?\s*$", plugin, re.MULTILINE):
            raise ValueError(f"Built plugin.yml version does not match lcVersion: {path.relative_to(ROOT)}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--list-source", action="store_true", help="Print every selected source path; write nothing")
    parser.add_argument("--check-source", action="store_true", help="Validate source selection; write nothing")
    args = parser.parse_args()
    release = version()
    paths = source_files()
    notes = ROOT / f"docs/RELEASE-{release}.md"
    if notes not in paths:
        raise FileNotFoundError(f"Missing current release notes: {notes.relative_to(ROOT)}")
    if args.list_source:
        print("\n".join(p.relative_to(ROOT).as_posix() for p in paths))
        return
    if args.check_source:
        print(json.dumps({"version": release, "source_files": len(paths), "output": f"dist/{release}"}))
        return
    built = ROOT / f"lc-core/build/libs/LunarCore-{release}.jar"
    if not built.is_file():
        raise FileNotFoundError(f"Build and verify {built.relative_to(ROOT)} first")
    validate_plugin(built, release, "LunarCore")
    with zipfile.ZipFile(built) as archive:
        for name in ("web/index.html", "builtin-modules/lc-qqauth.jar", "builtin-modules/lc-vote.jar"):
            archive.getinfo(name)
    bridges = []
    for project, plugin_name in BRIDGES:
        directory = ROOT / f"integrations/{project}/build/libs"
        candidates = (directory / f"{plugin_name}.jar", directory / f"{project}.jar")
        bridge = next((path for path in candidates if path.is_file()), None)
        if bridge is not None:
            validate_plugin(bridge, release, plugin_name)
            bridges.append((bridge, f"{plugin_name}.jar"))
    output = ROOT / "dist" / release
    output.mkdir(parents=True, exist_ok=True)
    jar = output / f"LunarCore-{release}.jar"
    source = output / f"LunarCore-{release}-source.zip"
    bundle = output / f"LunarCore-{release}.zip"
    shutil.copyfile(built, jar)
    packaged_bridges = []
    for built_bridge, name in bridges:
        bridge = output / name
        shutil.copyfile(built_bridge, bridge)
        packaged_bridges.append(bridge)
    manifest = {p.relative_to(ROOT).as_posix(): digest(p) for p in paths}
    with zipfile.ZipFile(source, "w") as archive:
        for path in paths:
            add_file(archive, path, "LunarCore/" + path.relative_to(ROOT).as_posix())
        add_bytes(archive, "SOURCE-SHA256.json", (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
    instructions = f"""LunarCore {release} / Java 25 / Paper 26.3 build 202
本轮版本重新从 alpha1 编号，后续仅使用 alphaN；重编号前本地旧交付不作为当前版本。
先备份并停服，替换 plugins 中的 LunarCore JAR；不要留下多个版本，也不要热覆盖。
QQAuth 默认不自动启动；配置群组和桥接后停服设 modules.qqauth-autostart: true，且从 modules.disabled 移除 qqauth。
QQAuth 启停可能踢出在线玩家，不要同时启用 AuthMe。内置优化默认关闭。
LcMultiverseWeb.jar 与 LcPlayerTitleWeb.jar 若随包提供，均为可选独立桥接插件，
仅在安装对应第三方插件时另放入 plugins；它们不在核心 JAR 内，不含第三方插件。
本包含源码与许可，不含 Paper、JDK、运行服、数据库或生成凭据。
阅读 docs/RELEASE-{release}.md 与所链接的测试清单，真实客户端/多人测试仍待验收。
"""
    with zipfile.ZipFile(bundle, "w") as archive:
        for path in (jar, source, *packaged_bridges):
            add_file(archive, path, path.name)
        for name in ("LICENSE", "THIRD-PARTY-NOTICES.md", f"docs/RELEASE-{release}.md",
                     "docs/Alpha2-人工反馈修复测试清单.md"):
            add_file(archive, ROOT / name, name)
        add_bytes(archive, "INSTALL.txt", instructions.encode("utf-8"))
    for path in (source, bundle):
        with zipfile.ZipFile(path) as archive:
            if archive.testzip() is not None:
                raise ValueError(f"ZIP integrity failed: {path.name}")
    checks = {p.name: digest(p) for p in (jar, source, *packaged_bridges, bundle)}
    (output / "SHA256SUMS.txt").write_text("".join(f"{value}  {name}\n" for name, value in checks.items()), encoding="utf-8")
    print(json.dumps({"version": release, "source_files": len(paths), "output": str(output), "sha256": checks}, indent=2))


if __name__ == "__main__":
    main()
