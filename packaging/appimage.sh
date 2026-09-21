#!/bin/sh
set -eu

ARCH_NAME="${1:-}"
case "$ARCH_NAME" in
  x64)
    APPIMAGE_ARCH="x86_64"
    TOOL_SHA256="ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0"
    ;;
  arm64)
    APPIMAGE_ARCH="aarch64"
    TOOL_SHA256="f0837e7448a0c1e4e650a93bb3e85802546e60654ef287576f46c71c126a9158"
    ;;
  *)
    echo "Usage: $0 x64|arm64" >&2
    exit 2
    ;;
esac

VERSION="$(tr -d '[:space:]' < VERSION)"
APPDIR="build/AppDir-${ARCH_NAME}"
TOOLS="build/appimage-tools"
TOOL="$TOOLS/appimagetool-${APPIMAGE_ARCH}.AppImage"
OUT="release_out/CDA-Free-Player-Linux-${ARCH_NAME}-v${VERSION}.AppImage"

rm -rf "$APPDIR"
mkdir -p "$APPDIR/usr/bin" "$TOOLS" release_out
cp -a dist/cda-free-player "$APPDIR/usr/bin/cda-free-player"
cp packaging/cda-free-player.desktop "$APPDIR/cda-free-player.desktop"
cp assets/linux/256x256/apps/cda-free-player.png "$APPDIR/cda-free-player.png"

cat > "$APPDIR/AppRun" <<'RUN'
#!/bin/sh
set -eu
HERE="${APPDIR:-$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)}"
exec "$HERE/usr/bin/cda-free-player/cda-free-player" "$@"
RUN
chmod +x "$APPDIR/AppRun"

if [ ! -f "$TOOL" ] || ! printf '%s  %s\n' "$TOOL_SHA256" "$TOOL" | sha256sum -c - >/dev/null 2>&1; then
  rm -f "$TOOL"
  curl -fL --retry 5 --retry-all-errors \
    "https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-${APPIMAGE_ARCH}.AppImage" \
    -o "$TOOL"
fi
printf '%s  %s\n' "$TOOL_SHA256" "$TOOL" | sha256sum -c -
chmod +x "$TOOL"

rm -f "$OUT"
ARCH="$APPIMAGE_ARCH" VERSION="$VERSION" APPIMAGE_EXTRACT_AND_RUN=1 \
  "$TOOL" "$APPDIR" "$OUT"
chmod +x "$OUT"
test -s "$OUT"

# CI runners may not provide FUSE. Extract-and-run exercises the exact AppImage
# payload without weakening the normal one-file UX for end users.
QT_QPA_PLATFORM=offscreen QTWEBENGINE_DISABLE_SANDBOX=1 APPIMAGE_EXTRACT_AND_RUN=1 \
  "$OUT" --self-test

printf '%s\n' "$OUT"
