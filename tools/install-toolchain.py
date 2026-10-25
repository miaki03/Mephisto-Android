"""下载并解压安卓工具链到 E:\\study\\.toolchain（不装到系统目录）。

为什么用 Python 下而不是 curl/Invoke-WebRequest：这台机器上走 schannel 的 HTTPS 会因
证书存储不可用而失败（沙箱环境），Python 的 TLS 正常。见 docs/STATE.md §2。

下三样：
  jdk      Adoptium 21 GA（Windows x64 zip）
  cmdline  Android command-line tools（从 Google 仓库 XML 里取最新）
  gradle   Gradle 当前稳定版
"""

from __future__ import annotations

import json
import shutil
import os
import sys
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

# 工具链根目录：优先 $env:MEPHISTO_TOOLCHAIN，否则用"仓库同级目录下的 .toolchain"
# 不要在这里写死盘符。
ROOT = Path(os.environ.get("MEPHISTO_TOOLCHAIN") or (Path(__file__).resolve().parents[2] / ".toolchain")).resolve()
DOWNLOADS = ROOT / "downloads"
UA = {"User-Agent": "mephisto-setup/1.0"}

JDK_URL = "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse"
SDK_REPO = "https://dl.google.com/android/repository/repository2-3.xml"
GRADLE_CURRENT = "https://services.gradle.org/versions/current"


def fetch(url: str, timeout: int = 60) -> bytes:
    request = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read()


def download(url: str, dest: Path, label: str) -> Path:
    DOWNLOADS.mkdir(parents=True, exist_ok=True)
    if dest.exists() and dest.stat().st_size > 1_000_000:
        print(f"  {label}: 已存在（{dest.stat().st_size / 1048576:.0f} MB），跳过", flush=True)
        return dest
    print(f"  {label}: 下载 {url}", flush=True)
    request = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(request, timeout=900) as response, open(dest, "wb") as handle:
        total = int(response.headers.get("Content-Length") or 0)
        done = 0
        next_report = 32
        while True:
            chunk = response.read(1 << 20)
            if not chunk:
                break
            handle.write(chunk)
            done += len(chunk)
            if total and done / 1048576 >= next_report:
                print(f"    {label} {done / 1048576:.0f}/{total / 1048576:.0f} MB", flush=True)
                next_report += 32
    print(f"  {label}: 完成 {dest.stat().st_size / 1048576:.0f} MB", flush=True)
    return dest


def unzip_to(zip_path: Path, target: Path) -> None:
    if target.exists():
        shutil.rmtree(target, ignore_errors=True)
    target.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(zip_path) as archive:
        archive.extractall(target)


def flatten_single(target: Path, dest: Path) -> None:
    """压缩包里通常只有一层目录，把它挪到 dest。"""
    entries = [item for item in target.iterdir() if item.is_dir()]
    if len(entries) == 1 and not any(item.is_file() for item in target.iterdir()):
        if dest.exists():
            shutil.rmtree(dest, ignore_errors=True)
        shutil.move(str(entries[0]), str(dest))
        target.rmdir()
    else:
        if dest.exists():
            shutil.rmtree(dest, ignore_errors=True)
        shutil.move(str(target), str(dest))


def cmdline_tools_url() -> tuple[str, str]:
    """从仓库 XML 里挑 cmdline-tools 的最新版本（不写死文件名，版本会变）。"""
    root = ET.fromstring(fetch(SDK_REPO, timeout=120))
    best: tuple[int, str, str] | None = None
    for package in root.iter("remotePackage"):
        path = package.get("path", "")
        if not path.startswith("cmdline-tools"):
            continue
        revision = package.find("revision")
        if revision is None:
            continue
        major = int(revision.findtext("major") or 0)
        minor = int(revision.findtext("minor") or 0)
        micro = int(revision.findtext("micro") or 0)
        score = major * 1_000_000 + minor * 1_000 + micro
        for archive in package.iter("archive"):
            host = archive.findtext("host-os")
            if host not in (None, "windows"):
                continue
            complete = archive.find("complete")
            if complete is None:
                continue
            url = complete.findtext("url")
            if url:
                if best is None or score > best[0]:
                    best = (score, url, path)
    if best is None:
        raise RuntimeError("在仓库 XML 里没找到 cmdline-tools")
    url = best[1]
    if not url.startswith("http"):
        url = "https://dl.google.com/android/repository/" + url
    return url, best[2]


def main() -> int:
    ROOT.mkdir(parents=True, exist_ok=True)
    print(f"工具链根目录 {ROOT}", flush=True)

    # 1. JDK
    print("[1/3] JDK 21", flush=True)
    jdk_zip = download(JDK_URL, DOWNLOADS / "jdk21.zip", "jdk")
    staging = ROOT / "_jdk_tmp"
    unzip_to(jdk_zip, staging)
    flatten_single(staging, ROOT / "jdk")
    print(f"  -> {ROOT / 'jdk'}", flush=True)

    # 2. cmdline-tools
    print("[2/3] Android command-line tools", flush=True)
    url, package_path = cmdline_tools_url()
    print(f"  仓库 XML 选中的包：{package_path}", flush=True)
    tools_zip = download(url, DOWNLOADS / "cmdline-tools.zip", "cmdline-tools")
    sdk = ROOT / "android-sdk"
    (sdk / "cmdline-tools").mkdir(parents=True, exist_ok=True)
    staging = sdk / "cmdline-tools" / "_tmp"
    unzip_to(tools_zip, staging)
    flatten_single(staging, sdk / "cmdline-tools" / "latest")
    print(f"  -> {sdk / 'cmdline-tools' / 'latest'}", flush=True)

    # 3. Gradle
    print("[3/3] Gradle", flush=True)
    info = json.loads(fetch(GRADLE_CURRENT))
    version = info["version"]
    print(f"  当前稳定版 {version}", flush=True)
    gradle_zip = download(info["downloadUrl"], DOWNLOADS / f"gradle-{version}-bin.zip", "gradle")
    staging = ROOT / "_gradle_tmp"
    unzip_to(gradle_zip, staging)
    flatten_single(staging, ROOT / "gradle")
    print(f"  -> {ROOT / 'gradle'}（{version}）", flush=True)

    print("全部解压完成", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
