import platform
import sys
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


def _test_dash_launcher_contract():
    from unittest.mock import patch
    from .player import Player

    player = Player.__new__(Player)
    profile = Path("profile-test")

    for system_name in ("Linux", "Windows", "Darwin"):
        with patch("cda_free_player.player.platform.system", return_value=system_name):
            shell = player._dash_browser_command("browser-test", profile)
            app = player._dash_app_command("browser-test", profile)
            direct = player._dash_direct_app_command("browser-test", profile)

        expected_profile = f"--user-data-dir={profile}"
        if expected_profile not in shell or expected_profile not in app or expected_profile not in direct:
            raise RuntimeError(f"DASH browser commands do not share one profile on {system_name}")
        if "--no-startup-window" not in shell:
            raise RuntimeError(f"DASH prewarm must start without a window on {system_name}")
        if "--start-minimized" in shell or "--start-minimized" in app or "--start-minimized" in direct:
            raise RuntimeError(f"DASH launcher must never start minimized on {system_name}")
        if not any(arg.startswith("--remote-debugging-port=") for arg in shell):
            raise RuntimeError(f"DASH prewarm missing remote debugging on {system_name}")
        if "--start-maximized" not in app or not any(arg.startswith("--app=") for arg in app):
            raise RuntimeError(f"DASH app window contract is incomplete on {system_name}")
        if "--no-startup-window" in app:
            raise RuntimeError(f"DASH app window must be visible on {system_name}")
        if "--no-startup-window" in direct:
            raise RuntimeError(f"DASH direct fallback must be visible on {system_name}")
        if not any(arg.startswith("--remote-debugging-port=") for arg in direct):
            raise RuntimeError(f"DASH direct fallback missing remote debugging on {system_name}")
        linux_class = any(arg.startswith("--class=") for arg in shell + app + direct)
        if system_name == "Linux" and not linux_class:
            raise RuntimeError("Linux DASH windows must expose the CDA Free Player WM class")
        if system_name != "Linux" and linux_class:
            raise RuntimeError(f"Linux-only WM class leaked into {system_name} launcher")

    win = Player._dash_browser_candidates("windows")
    mac = Player._dash_browser_candidates("darwin")
    linux = Player._dash_browser_candidates("linux")
    if not win or "edge" not in win[0].lower():
        raise RuntimeError("Windows DASH browser preference must start with Microsoft Edge")
    if not mac or "Google Chrome.app" not in mac[0]:
        raise RuntimeError("macOS DASH browser preference must start with Google Chrome")
    if not linux or "google-chrome" not in linux[0]:
        raise RuntimeError("Linux DASH browser preference must start with Google Chrome")


def _test_webview_challenge_contract():
    source = (Path(__file__).with_name("webview_worker.py")).read_text(encoding="utf-8")
    if "_pywebviewready" in source or "bridge_ready.wait" in source:
        raise RuntimeError("Cloudflare worker must not depend on pywebview JS bridge readiness")
    if "backend.evaluate_js(script, uid, False)" not in source:
        raise RuntimeError("Cloudflare worker must use native WebView evaluation")
    if 'focus=(gui == "gtk")' not in source:
        raise RuntimeError("Windows/macOS verification WebViews must be non-activating")
    if "SWP_NOACTIVATE" not in source or "HWND_BOTTOM" not in source:
        raise RuntimeError("Windows verification must stay behind the main app without activation")
    if "WS_EX_TOOLWINDOW" not in source:
        raise RuntimeError("Windows verification helper must stay out of the taskbar/Alt-Tab")
    if "orderBack_" not in source:
        raise RuntimeError("macOS verification must stay behind the main app")
    if "setActivationPolicy_(1)" not in source:
        raise RuntimeError("macOS verification helper must run as an accessory app")
    part = source[source.index("def show_verification"):source.index("def fetch(req)")]
    if "activateIgnoringOtherApps_" in part:
        raise RuntimeError("Verification must never activate the app on macOS")
    if "native.present" not in source:
        raise RuntimeError("Linux GTK verification behavior must remain intact")


def _test_desktop_ui_contract():
    source = (Path(__file__).with_name("ui.py")).read_text(encoding="utf-8")
    part = source[source.index("def build_left_nav"):source.index("def show_left_nav")]
    if 'text=APP_NAME' in part:
        raise RuntimeError("Desktop sidebar must show only the CDA logo")
    if '"Zamknąć aplikację?"' in source or '"Czy na pewno chcesz zamknąć CDA Free Player?"' in source:
        raise RuntimeError("Desktop app must close without a confirmation dialog")


def run_contracts():
    _test_dash_launcher_contract()
    _test_webview_challenge_contract()
    _test_desktop_ui_contract()
    print("CDA Free Player source contracts OK")
    return 0


def run():
    import httpx
    import bs4
    import PIL
    import PIL._tkinter_finder
    import PIL._imagingtk

    system = platform.system().lower()
    if not getattr(sys, "frozen", False):
        run_contracts()

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
    else:
        raise RuntimeError(f"Unsupported desktop OS: {system}")

    from .config import APP_ICON, resource_root
    if not Path(APP_ICON).exists():
        raise RuntimeError(f"Missing packaged icon: {APP_ICON}")
    for asset in ("dash.all.min.js", "player.js"):
        path = resource_root() / "assets" / "desktop" / asset
        if not path.is_file():
            raise RuntimeError(f"Missing desktop player asset: {path}")
    if not VERSION or VERSION == "0.0.0":
        raise RuntimeError(f"Invalid packaged VERSION: {VERSION!r}")

    if system == "windows":
        from .player import Player
        if not Path(Player.find_mpv()).is_file():
            raise RuntimeError("mpv.exe missing")

    print(f"CDA Free Player self-test OK | {system} | {platform.machine()} | {VERSION}")
    return 0


if __name__ == "__main__":
    if "--contracts" in sys.argv:
        raise SystemExit(run_contracts())
    raise SystemExit(run())
