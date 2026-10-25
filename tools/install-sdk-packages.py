"""手动安装 SDK 包（绕开 sdkmanager 的网络层）。

背景：这台机器上 `sdkmanager` / 新的 `android sdk` 都报 "Failed to connect to remote
repository"，而 Python 访问 dl.google.com 正常。所以直接从仓库 XML 拿 URL、用 Python 下载、
手工解压到正确目录。Android SDK 的目录结构是稳定的：

  platform-tools/platform-tools/...      -> <sdk>/platform-tools/
  platforms/android-36/...               -> <sdk>/platforms/android-36/
  build-tools/android-XX/...             -> <sdk>/build-tools/36.0.0/

用法：python tools/install-sdk-packages.py [--api 36] [--build-tools 36.0.0]
"""

from __future__ import annotations

import argparse
import re
import shutil
import sys
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(r"E:\study\.toolchain")
SDK = ROOT / "android-sdk"
DOWNLOADS = ROOT / "downloads"
REPO = "https://dl.google.com/android/repository/repository2-3.xml"
UA = {"User-Agent": "mephisto-setup/1.0"}


def fetch(url: str, timeout: int = 120) -> bytes:
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=timeout) as response:
        return response.read()


def download(url: str, dest: Path, label: str) -> Path:
    DOWNLOADS.mkdir(parents=True, exist_ok=True)
    if dest.exists() and dest.stat().st_size > 100_000:
        print(f"  {label}: 已存在，跳过", flush=True)
        return dest
    print(f"  {label}: 下载 {url}", flush=True)
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=900) as response, open(dest, "wb") as handle:
        shutil.copyfileobj(response, handle)
    print(f"  {label}: {dest.stat().st_size / 1048576:.1f} MB", flush=True)
    return dest


def packages() -> dict[str, str]:
    root = ET.fromstring(fetch(REPO))
    found: dict[str, str] = {}
    for package in root.iter("remotePackage"):
        path = package.get("path", "")
        for archive in package.iter("archive"):
            host = archive.findtext("host-os")
            if host not in (None, "windows", "any"):
                continue
            complete = archive.find("complete")
            if complete is None:
                continue
            url = complete.findtext("url") or ""
            if not url:
                continue
            if not url.startswith("http"):
                url = "https://dl.google.com/android/repository/" + url
            found[path] = url
    return found


def extract_single(zip_path: Path, dest: Path) -> None:
    staging = DOWNLOADS / (zip_path.stem + "_x")
    if staging.exists():
        shutil.rmtree(staging, ignore_errors=True)
    staging.mkdir(parents=True)
    with zipfile.ZipFile(zip_path) as archive:
        archive.extractall(staging)
    entries = list(staging.iterdir())
    source = entries[0] if len(entries) == 1 and entries[0].is_dir() else staging
    if dest.exists():
        shutil.rmtree(dest, ignore_errors=True)
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.move(str(source), str(dest))
    if staging.exists():
        shutil.rmtree(staging, ignore_errors=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--api", type=int, default=36)
    parser.add_argument("--build-tools", default="36.0.0")
    parser.add_argument("--skip-platform-tools", action="store_true")
    args = parser.parse_args()

    available = packages()
    plan: list[tuple[str, Path]] = []

    platform_key = f"platforms;android-{args.api}"
    if platform_key not in available:
        print(f"仓库里没有 {platform_key}", file=sys.stderr)
        return 2
    plan.append((platform_key, SDK / "platforms" / f"android-{args.api}"))

    tools_key = f"build-tools;{args.build_tools}"
    if tools_key not in available:
        print(f"仓库里没有 {tools_key}", file=sys.stderr)
        return 2
    plan.append((tools_key, SDK / "build-tools" / args.build_tools))

    if not args.skip_platform_tools and "platform-tools" in available:
        plan.append(("platform-tools", SDK / "platform-tools"))

    for key, dest in plan:
        url = available[key]
        name_match = re.search(r"/([^/]+\.zip)$", url)
        filename = name_match.group(1) if name_match else key.replace(";", "-").replace(".", "_") + ".zip"
        print(f"[{key}]", flush=True)
        zip_path = download(url, DOWNLOADS / filename, key)
        extract_single(zip_path, dest)
        print(f"  -> {dest}", flush=True)

    print("完成。检查关键文件：", flush=True)
    for probe in (
        SDK / "platforms" / f"android-{args.api}" / "android.jar",
        SDK / "build-tools" / args.build_tools / "aapt2.exe",
        SDK / "platform-tools" / "adb.exe",
    ):
        print(f"  {'OK ' if probe.exists() else 'MISS'} {probe}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
