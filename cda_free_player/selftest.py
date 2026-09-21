import platform
import sys
from pathlib import Path

from .version import VERSION


def run():
    # Core imports that must exist in every frozen desktop package.
    import httpx  # noqa: F401
    import bs4  # noqa: F401
    import PIL  # noqa: F401
    import webview  # noqa: F401

    system = platform.system().lower()
    if system == "linux":
        import PyQt6.QtCore  # noqa: F401
        import PyQt6.QtWebEngineCore  # noqa: F401
        import PyQt6.QtWebEngineWidgets  # noqa: F401
        import webview.platforms.qt  # noqa: F401
    elif system == "windows":
        import webview.platforms.winforms  # noqa: F401
        import webview.platforms.edgechromium  # noqa: F401
    elif system == "darwin":
        import webview.platforms.cocoa  # noqa: F401
    else:
        raise RuntimeError(f"Unsupported desktop OS: {system}")

    # Validate packaged resources and version path handling.
    from .config import APP_ICON
    if not Path(APP_ICON).exists():
        raise RuntimeError(f"Missing packaged icon: {APP_ICON}")
    if not VERSION or VERSION == "0.0.0":
        raise RuntimeError(f"Invalid packaged VERSION: {VERSION!r}")

    print(f"CDA Free Player self-test OK | {system} | {platform.machine()} | {VERSION}")
    return 0
