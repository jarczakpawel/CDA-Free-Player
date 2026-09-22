from datetime import datetime
from pathlib import Path
import os
import platform
import shutil
import sys

BASE = "https://www.cda.pl"
CURRENT_YEAR = datetime.now().year
DEFAULT_YEAR = 1985

APP_NAME = "CDA Free Player"
APP_SLUG = "cda-free-player"
AUTHOR = "Paweł Jarczak"
REPOSITORY_URL = "https://github.com/jarczakpawel/CDA-Free-Player"

def _state_dir():
    home = Path.home()
    system = platform.system().lower()

    if system == "windows":
        base = Path(os.environ.get("LOCALAPPDATA", home / "AppData" / "Local"))
        return base / APP_NAME

    if system == "darwin":
        return home / "Library" / "Application Support" / APP_NAME

    base = Path(os.environ.get("XDG_DATA_HOME", home / ".local" / "share"))
    return base / APP_SLUG


STATE_DIR = _state_dir()

                                                                         
                                                                            
_legacy_dirs = [
    Path.home() / ".local" / "share" / "cda-tv",
]
if not STATE_DIR.exists():
    for _legacy in _legacy_dirs:
        if _legacy.exists() and _legacy != STATE_DIR:
            try:
                STATE_DIR.parent.mkdir(parents=True, exist_ok=True)
                shutil.move(str(_legacy), str(STATE_DIR))
                break
            except OSError:
                pass

PROFILE_DIR = STATE_DIR / "browser"
SESSION_FILE = STATE_DIR / "session.json"
DB_FILE = STATE_DIR / "cda-free-player.db"
LOG_DIR = STATE_DIR / "logs"
THUMB_DIR = STATE_DIR / "thumbs"
LOG_FILE = LOG_DIR / "cda-free-player.log"

                                                                         
_legacy_db = STATE_DIR / "cda-tv.db"
if not DB_FILE.exists() and _legacy_db.exists():
    try:
        _legacy_db.rename(DB_FILE)
    except OSError:
        pass

BG = "#101216"
PANEL = "#171a20"
PANEL2 = "#1f232b"
CARD = "#20242c"
CARD_FOCUS = "#343c49"
TEXT = "#f2f2f2"
MUTED = "#aeb4bf"
ACCENT = "#55aaff"
SELECTED = "#245986"
BORDER = "#343a44"

GRID_COLS = 4
INITIAL_TARGET = 12
INITIAL_MAX_PAGES = 4
LOAD_AHEAD = 4
CACHE_TTL = 6 * 60 * 60
META_DELAY_MS = 700
SEARCH_PAGE_LIMIT = 20

for path in (STATE_DIR, LOG_DIR, THUMB_DIR):
    path.mkdir(parents=True, exist_ok=True)


def resource_root():
    bundled = getattr(sys, "_MEIPASS", None)
    if bundled:
        return Path(bundled)
    return Path(__file__).resolve().parent.parent

ASSET_DIR = resource_root() / "assets"
APP_ICON = ASSET_DIR / "cda-free-player.png"

WEBVIEW_STORAGE_DIR = STATE_DIR / "native-webview"
WEBVIEW_STORAGE_DIR.mkdir(parents=True, exist_ok=True)
