from pathlib import Path


def get_version():
    root = Path(__file__).resolve().parent.parent
    version_file = root / "VERSION"
    try:
        return version_file.read_text(encoding="utf-8").strip() or "1.0.12"
    except OSError:
        return "1.0.12"


VERSION = get_version()
