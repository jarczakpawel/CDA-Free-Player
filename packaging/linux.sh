#!/bin/sh
set -eu
python -m pip install --disable-pip-version-check -r requirements.txt -r requirements-dev.txt
pyinstaller --noconfirm --clean --windowed --name "cda-free-player" \
  --icon "assets/cda-free-player.png" \
  --add-data "assets:assets" \
  --add-data "VERSION:." \
  --hidden-import "webview.platforms.qt" \
  --exclude-module "webview.platforms.android" \
  --exclude-module "webview.platforms.gtk" \
  --exclude-module "webview.platforms.cocoa" \
  --exclude-module "webview.platforms.winforms" \
  --exclude-module "webview.platforms.winui3" \
  --exclude-module "webview.platforms.cef" \
  desktop_entry.py
