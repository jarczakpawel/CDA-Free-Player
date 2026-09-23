import json
import logging
import os
import sys
import threading
import time
from pathlib import Path
from urllib.parse import urlparse


def is_challenge(html):
    value = (html or "").lower()
    return any(marker in value for marker in (
        "przeprowadzanie weryfikacji zabezpieczeń", "checking if you are not a bot",
        "verify you are human", "<title>just a moment", "id=\"challenge-form\"",
        "id=\"challenge-running\"", "attention required! | cloudflare",
    ))


def serialize_cookies(cookies):
    out = []
    for cookie in cookies or []:
        for name, morsel in cookie.items():
            out.append({
                "name": str(name), "value": str(morsel.value),
                "domain": str(morsel["domain"] or ".cda.pl"),
                "path": str(morsel["path"] or "/"), "secure": bool(morsel["secure"]),
            })
    return out


def run_webview_worker(conn, storage, icon, gui, script_path):
    if sys.stdout is None:
        sys.stdout = open(os.devnull, "w")
    if sys.stderr is None:
        sys.stderr = open(os.devnull, "w")

    try:
        import webview

        if gui == "cocoa":
            from webview.platforms import cocoa
            cocoa.BrowserView.app.setActivationPolicy_(1)

            def first_show(view):
                if not view.hidden:
                    view.window.makeKeyAndOrderFront_(view.window)
                if view.maximized:
                    view.maximize()
                elif view.minimized:
                    view.minimize()
                view.shown.set()
                if not cocoa.BrowserView.app.isRunning():
                    cocoa.BrowserView.app.setMainMenu_(view._recreate_menus(view.menu))
                    if not view.hidden:
                        cocoa.BrowserView.app.activateIgnoringOtherApps_(True)
                    cocoa.AppHelper.installMachInterrupt()
                    cocoa.BrowserView.app.run()

            cocoa.BrowserView.first_show = first_show

        if os.environ.get("CDAFP_DEBUG", "").strip() not in ("1", "true", "yes", "on"):
            logging.getLogger("pywebview").setLevel(logging.CRITICAL)

        capture = Path(script_path).read_text(encoding="utf-8")
        Path(storage).mkdir(parents=True, exist_ok=True)
        window = webview.create_window(
            "CDA Free Player", "about:blank", width=980, height=720,
            min_size=(640, 480), hidden=True, focus=(gui == "gtk"),
            background_color="#101216", text_select=True,
        )
    except Exception as exc:
        conn.send({"type": "fatal", "error": str(exc)})
        return

    loaded = threading.Event()
    window.events.loaded += loaded.set

    def native_eval(script):
        """Evaluate page JavaScript without depending on pywebview's injected JS bridge."""
        backend = getattr(window, "gui", None)
        uid = getattr(window, "uid", None)
        if backend is None or uid is None:
            raise RuntimeError("WebView backend is not ready")
        return backend.evaluate_js(script, uid, False)

    def show_verification():
        # Linux already behaves well with GTK present(). On Windows and macOS the
        # verification WebView must stay behind the main CDA window and never steal focus.
        if gui == "gtk":
            try:
                window.show()
            except Exception:
                pass
            try:
                window.restore()
            except Exception:
                pass
            try:
                from gi.repository import GLib
                native = window.native
                GLib.idle_add(native.present)
            except Exception:
                pass
            return

        if gui == "edgechromium":
            try:
                window.show()
            except Exception:
                pass
            try:
                import ctypes
                hwnd = int(window.native.Handle.ToInt32())
                GWL_EXSTYLE = -20
                WS_EX_TOOLWINDOW = 0x00000080
                WS_EX_NOACTIVATE = 0x08000000
                style = ctypes.windll.user32.GetWindowLongW(hwnd, GWL_EXSTYLE)
                ctypes.windll.user32.SetWindowLongW(
                    hwnd, GWL_EXSTYLE, style | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE,
                )
                HWND_BOTTOM = 1
                SWP_NOSIZE = 0x0001
                SWP_NOMOVE = 0x0002
                SWP_NOACTIVATE = 0x0010
                SWP_SHOWWINDOW = 0x0040
                ctypes.windll.user32.SetWindowPos(
                    hwnd, HWND_BOTTOM, 0, 0, 0, 0,
                    SWP_NOSIZE | SWP_NOMOVE | SWP_NOACTIVATE | SWP_SHOWWINDOW,
                )
            except Exception:
                pass
            return

        if gui == "cocoa":
            try:
                native = window.native
                cocoa.AppHelper.callAfter(native.orderBack_, None)
            except Exception:
                pass
            return

        try:
            window.show()
        except Exception:
            pass

    def fetch(req):
        request_id, url = req["id"], req["url"]
        expect_player = req.get("expect_player", False)
        conn.send({"type": "event", "event": "loading", "id": request_id, "url": url})
        loaded.clear()
        window.hide()
        window.load_url(url)
        deadline = time.monotonic() + 120
        clean_since = 0
        shown = False
        html = ""
        capture_url = ""
        started = time.monotonic()

        while time.monotonic() < deadline:
            if not loaded.wait(0.20):
                continue
            time.sleep(0.12)

            current_url = window.get_current_url() or ""
            if urlparse(current_url).hostname not in ("cda.pl", "www.cda.pl", "m.cda.pl"):
                continue

            target, actual = urlparse(url).path, urlparse(current_url).path
            if actual != target and not (expect_player and actual.startswith(target + "/")):
                continue

            try:
                state = native_eval("document.readyState")
                if state not in ("interactive", "complete"):
                    continue
                probe = native_eval(
                    "(document.title || '') + '\\n' + "
                    "(document.body ? document.body.innerText.slice(0, 2400) : '')"
                ) or ""
            except Exception:
                time.sleep(0.15)
                continue

            if is_challenge(probe):
                clean_since = 0
                if not shown:
                    shown = True
                    conn.send({"type": "event", "event": "interactive", "id": request_id, "url": current_url})
                    window.set_title("CDA Free Player - Weryfikacja zabezpieczeń")
                    show_verification()
                time.sleep(0.20)
                continue

            if expect_player:
                try:
                    if capture_url != current_url:
                        native_eval(capture)
                        capture_url = current_url
                    raw = native_eval(
                        "window.__CDA_FP_READ_STRUCTURED ? window.__CDA_FP_READ_STRUCTURED() : ''"
                    ) or ""
                    if not raw and time.monotonic() - started >= 1.5:
                        raw = native_eval(
                            "window.__CDA_FP_READ_PLAYER ? window.__CDA_FP_READ_PLAYER() : ''"
                        ) or ""
                except Exception:
                    raw = ""
                if raw:
                    html = "__CDA_PLAYER_DATA__" + raw
                    break

            if not clean_since:
                clean_since = time.monotonic()
            settle = 12 if expect_player else 0.4
            if time.monotonic() - clean_since < settle:
                time.sleep(0.15)
                continue

            try:
                html = native_eval(
                    "document.documentElement ? document.documentElement.outerHTML : ''"
                ) or ""
            except Exception:
                html = ""

            if html:
                break
        else:
            raise RuntimeError("Przekroczono czas ładowania strony CDA")

        try:
            user_agent = native_eval("navigator.userAgent") or ""
        except Exception:
            user_agent = ""

        cookies = serialize_cookies(window.get_cookies())
        window.hide()
        window.set_title("CDA Free Player")
        conn.send({
            "type": "result", "id": request_id, "ok": True, "html": html,
            "url": current_url, "user_agent": user_agent, "cookies": cookies,
        })

    def command_loop():
        conn.send({"type": "ready"})
        while True:
            try:
                req = conn.recv()
            except (EOFError, OSError):
                window.destroy()
                return

            if req.get("cmd") == "quit":
                window.destroy()
                return

            if req.get("cmd") == "fetch":
                try:
                    fetch(req)
                except Exception as exc:
                    window.hide()
                    conn.send({"type": "result", "id": req.get("id"), "ok": False, "error": str(exc)})

    try:
        kwargs = dict(private_mode=False, storage_path=storage, debug=False)
        if gui == "gtk" and icon and Path(icon).exists():
            kwargs["icon"] = icon
        webview.start(command_loop, gui=gui, **kwargs)
    except Exception as exc:
        conn.send({"type": "fatal", "error": "WebView: " + str(exc)})
