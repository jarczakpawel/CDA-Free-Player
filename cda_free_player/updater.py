import json
import platform
import webbrowser
from dataclasses import dataclass

import httpx

from .config import REPOSITORY_URL
from .version import VERSION

API_LATEST = "https://api.github.com/repos/jarczakpawel/CDA-Free-Player/releases/latest"


def _version_tuple(value):
    value = str(value or "").strip().lstrip("vV")
    parts = value.split(".")
    out = []
    for part in parts[:3]:
        digits = "".join(ch for ch in part if ch.isdigit())
        out.append(int(digits or 0))
    while len(out) < 3:
        out.append(0)
    return tuple(out)


def is_newer(remote, current=VERSION):
    return _version_tuple(remote) > _version_tuple(current)


def desktop_asset_key():
    system = platform.system().lower()
    machine = platform.machine().lower()
    arm = machine in {"arm64", "aarch64"} or machine.startswith("arm")
    if system == "windows":
        return "windows-x64"
    if system == "darwin":
        return "macos-arm64" if arm else "macos-x64"
    if system == "linux":
        return "linux-arm64" if arm else "linux-x64"
    return None


@dataclass
class UpdateInfo:
    current: str
    latest: str
    available: bool
    release_url: str
    asset_url: str = ""
    asset_name: str = ""
    sha256: str = ""


def check_latest(timeout=7.0):
    headers = {
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2026-03-10",
        "User-Agent": "CDA-Free-Player-Updater",
    }
    with httpx.Client(timeout=timeout, follow_redirects=True, headers=headers) as client:
        r = client.get(API_LATEST)
        r.raise_for_status()
        release = r.json()
        latest = str(release.get("tag_name") or "").lstrip("vV")
        release_url = release.get("html_url") or (REPOSITORY_URL + "/releases/latest")
        assets = release.get("assets") or []
        by_name = {str(a.get("name")): a for a in assets}

        manifest = None
        manifest_asset = by_name.get("update-manifest.json")
        if manifest_asset and manifest_asset.get("browser_download_url"):
            mr = client.get(manifest_asset["browser_download_url"])
            mr.raise_for_status()
            manifest = mr.json()

        asset_url = ""
        asset_name = ""
        sha256 = ""
        key = desktop_asset_key()
        if manifest and key:
            data = (manifest.get("assets") or {}).get(key) or {}
            asset_name = str(data.get("name") or "")
            sha256 = str(data.get("sha256") or "")
            if asset_name in by_name:
                asset_url = str(by_name[asset_name].get("browser_download_url") or "")

        return UpdateInfo(
            current=VERSION,
            latest=latest or VERSION,
            available=bool(latest and is_newer(latest, VERSION)),
            release_url=release_url,
            asset_url=asset_url,
            asset_name=asset_name,
            sha256=sha256,
        )


def open_update(info):
    url = info.asset_url or info.release_url or (REPOSITORY_URL + "/releases/latest")
    webbrowser.open(url)
