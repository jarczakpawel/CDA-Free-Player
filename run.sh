#!/bin/sh
set -eu
cd "$(dirname "$0")"
if [ ! -x .venv/bin/python ]; then
  echo "Najpierw uruchom: ./bootstrap-linux.sh" >&2
  exit 2
fi
exec ./.venv/bin/python -m cda_free_player.main "$@"
