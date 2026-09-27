#!/bin/sh
set -eu
python -m pip install --disable-pip-version-check -r requirements.txt -r requirements-dev.txt
python -m cda_free_player.selftest --contracts
iconutil -c icns "assets/macos/cda-free-player.iconset" -o "assets/macos/cda-free-player.icns"
pyinstaller --noconfirm --clean --windowed --name "CDA Free Player" \
  --icon "assets/macos/cda-free-player.icns" \
  --add-data "assets:assets" \
  --add-data "VERSION:." \
  --hidden-import "PIL._tkinter_finder" \
  --hidden-import "PIL._imagingtk" \
  --hidden-import "webview.platforms.cocoa" \
  --exclude-module "webview.platforms.android" \
  --exclude-module "webview.platforms.gtk" \
  --exclude-module "webview.platforms.qt" \
  --exclude-module "webview.platforms.winforms" \
  --exclude-module "webview.platforms.winui3" \
  --exclude-module "webview.platforms.cef" \
  desktop_entry.py
