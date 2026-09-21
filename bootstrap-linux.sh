#!/bin/sh
set -eu
cd "$(dirname "$0")"

if [ ! -d .venv ]; then
  python3 -m venv .venv
fi

. .venv/bin/activate
python -m pip install --upgrade pip
pip install -r requirements.txt

echo
echo "Gotowe. Uruchom:"
echo "  ./run.sh"
