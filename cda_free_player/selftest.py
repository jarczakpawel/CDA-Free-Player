import platform
from pathlib import Path

from .version import VERSION


def _test_tk():
    import tkinter as tk
    from PIL import Image, ImageTk

    root = tk.Tk()
    root.withdraw()
    try:
        image = ImageTk.PhotoImage(Image.new("RGB", (2, 2)), master=root)
        if image.width() != 2:
            raise RuntimeError("Pillow/Tk image creation failed")
    finally:
        root.destroy()


def run():
    import httpx
    import bs4
    import PIL
    import PIL._tkinter_finder
    import PIL._imagingtk

    system = platform.system().lower()

    if system == "darwin":
        _test_tk()
        import webview
        import webview.platforms.cocoa
    elif system == "windows":
        import webview
        import webview.platforms.winforms
        import webview.platforms.edgechromium
        _test_tk()
    elif system == "linux":
        import tkinter
        import PIL.ImageTk
        import gi
        gi.require_version("Gtk", "3.0")
        gi.require_version("WebKit2", "4.1")
        from gi.repository import Gtk, WebKit2
        import webview
        import webview.platforms.gtk
    else:
        raise RuntimeError(f"Unsupported desktop OS: {system}")

    from .config import APP_ICON
    if not Path(APP_ICON).exists():
        raise RuntimeError(f"Missing packaged icon: {APP_ICON}")
    if not VERSION or VERSION == "0.0.0":
        raise RuntimeError(f"Invalid packaged VERSION: {VERSION!r}")

    if system == "windows":
        from .player import Player
        if not Path(Player.find_mpv()).is_file():
            raise RuntimeError("mpv.exe missing")

    print(f"CDA Free Player self-test OK | {system} | {platform.machine()} | {VERSION}")
    return 0


if __name__ == "__main__":
    raise SystemExit(run())
