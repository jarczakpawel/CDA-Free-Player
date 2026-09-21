import hashlib
import json
import sys
from pathlib import Path

root = Path(__file__).resolve().parent.parent
version = (root / "VERSION").read_text(encoding="utf-8").strip()
tag = f"v{version}"
out_dir = Path(sys.argv[1] if len(sys.argv) > 1 else "release-files")

names = {
    "linux-x64": f"CDA-Free-Player-Linux-x64-{tag}.AppImage",
    "linux-arm64": f"CDA-Free-Player-Linux-arm64-{tag}.AppImage",
    "windows-x64": f"CDA-Free-Player-Windows-x64-{tag}.zip",
    "macos-x64": f"CDA-Free-Player-macOS-x64-{tag}.zip",
    "macos-arm64": f"CDA-Free-Player-macOS-arm64-{tag}.zip",
    "android-universal": f"CDA-Free-Player-AndroidTV-{tag}.apk",
}

def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()

assets = {}
missing = []
for key, name in names.items():
    p = out_dir / name
    if not p.exists():
        missing.append(name)
        continue
    item = {"name": name, "sha256": digest(p), "size": p.stat().st_size}
    if key == "android-universal":
        parts = [int(x) for x in version.split(".")[:3]]
        while len(parts) < 3:
            parts.append(0)
        item.update({
            "min_sdk": 28,
            "package": "pl.paweljarczak.cdafreeplayer",
            "version_code": parts[0] * 1000000 + parts[1] * 1000 + parts[2],
        })
    assets[key] = item

if missing:
    raise SystemExit("Missing release assets: " + ", ".join(missing))

manifest = {
    "schema": 1,
    "version": version,
    "tag": tag,
    "repository": "jarczakpawel/CDA-Free-Player",
    "assets": assets,
}
(out_dir / "update-manifest.json").write_text(
    json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)
print(out_dir / "update-manifest.json")
