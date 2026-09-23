#!/bin/sh
set -eu
ARCH="${1:-x64}"
VERSION="$(tr -d '[:space:]' < VERSION)"
ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
STAGE="$ROOT/build/linux-release/cda-free-player"
OUT="$ROOT/release_out/CDA-Free-Player-Linux-${ARCH}-v${VERSION}.tar.gz"

rm -rf "$ROOT/build/linux-release"
mkdir -p "$STAGE" "$ROOT/release_out"
cp -a "$ROOT/cda_free_player" "$STAGE/"
mkdir -p "$STAGE/assets"
cp "$ROOT/assets/cda-free-player.png" "$STAGE/assets/"
cp -a "$ROOT/assets/web" "$STAGE/assets/"
cp -a "$ROOT/assets/desktop" "$STAGE/assets/"
cp "$ROOT/VERSION" "$ROOT/desktop_entry.py" "$ROOT/requirements-linux.txt" "$STAGE/"
cp "$ROOT/bootstrap-linux.sh" "$ROOT/run.sh" "$STAGE/"
cp "$ROOT/packaging/cda-free-player.desktop" "$STAGE/"
find "$STAGE" -type d -name '__pycache__' -prune -exec rm -rf {} +
find "$STAGE" -type f -name '*.pyc' -delete
chmod +x "$STAGE/bootstrap-linux.sh" "$STAGE/run.sh"

tar -C "$ROOT/build/linux-release" -czf "$OUT" cda-free-player
test -s "$OUT"
echo "$OUT"
