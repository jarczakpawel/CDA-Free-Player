#!/bin/sh
set -eu
cd "$(dirname "$0")"

if command -v apt-get >/dev/null 2>&1; then
  echo "Instaluję systemowe zależności CDA Free Player (GTK/WebKit/Tk/mpv)..."
  sudo apt-get update
  sudo apt-get install -y --no-install-recommends \
    python3 python3-venv python3-pip python3-tk \
    python3-gi python3-gi-cairo \
    gir1.2-gtk-3.0 gir1.2-webkit2-4.1 \
    mpv
else
  echo "Ten instalator obsługuje Debian/Ubuntu." >&2
  echo "Zainstaluj: Python 3 + venv + Tk, PyGObject/GTK3, WebKitGTK 4.1 oraz mpv." >&2
  exit 2
fi

rm -rf .venv
python3 -m venv --system-site-packages .venv
. .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install -r requirements-linux.txt
python -m cda_free_player.selftest

echo
echo "Gotowe. Uruchom:"
echo "  ./run.sh"
