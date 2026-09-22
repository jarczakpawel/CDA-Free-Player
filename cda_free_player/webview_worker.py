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
    if sys.stdout is None: sys.stdout = open(os.devnull, "w")
    if sys.stderr is None: sys.stderr = open(os.devnull, "w")
    try:
        import webview
        if os.environ.get("CDAFP_DEBUG", "").strip() not in ("1", "true", "yes", "on"):
            logging.getLogger("pywebview").setLevel(logging.CRITICAL)
        capture = Path(script_path).read_text(encoding="utf-8")
        Path(storage).mkdir(parents=True, exist_ok=True)
        window = webview.create_window(
            "CDA Free Player", "about:blank", width=980, height=720,
            min_size=(640, 480), hidden=True, focus=True,
            background_color="#101216", text_select=True,
        )
    except Exception as exc:
        conn.send({"type": "fatal", "error": str(exc)})
        return
    loaded = threading.Event()
    window.events.loaded += loaded.set
    bridge_ready = getattr(window.events, "_pywebviewready", None)
    if bridge_ready is not None:
        window.events.before_load += bridge_ready.clear

    def fetch(req):
        request_id, url = req["id"], req["url"]
        expect_player = req.get("expect_player", False)
        conn.send({"type": "event", "event": "loading", "id": request_id, "url": url})
        loaded.clear()
        if bridge_ready is not None:
            bridge_ready.clear()
        window.hide()
        window.load_url(url)
        deadline = time.monotonic() + 120
        clean_since = 0
        shown = False
        html = ""
        capture_url = ""
        started = time.monotonic()

        while time.monotonic() < deadline:
            if not loaded.wait(0.25):
                continue
            time.sleep(0.15)
            current_url = window.get_current_url() or ""
            if urlparse(current_url).hostname not in ("cda.pl", "www.cda.pl", "m.cda.pl"):
                continue
            target, actual = urlparse(url).path, urlparse(current_url).path
            if actual != target and not (expect_player and actual.startswith(target + "/")):
                continue
            if bridge_ready is not None and not bridge_ready.wait(4):
                if not shown and time.monotonic() - started >= 1.5:
                    shown = True
                    conn.send({"type": "event", "event": "interactive", "id": request_id, "url": current_url})
                    window.set_title("CDA Free Player - Weryfikacja zabezpieczeń")
                    window.show()
                continue

            try:
                state = window.evaluate_js("document.readyState")
                if state != "complete":
                    continue
                probe = window.evaluate_js(
                    "(document.title || '') + '\n' + "
                    "(document.body ? document.body.innerText.slice(0, 2400) : '')"
                ) or ""
            except Exception:
                if bridge_ready is not None:
                    bridge_ready.clear()
                time.sleep(0.2)
                continue

            if is_challenge(probe):
                clean_since = 0
                if not shown:
                    shown = True
                    conn.send({"type": "event", "event": "interactive", "id": request_id, "url": current_url})
                    window.set_title("CDA Free Player - Weryfikacja zabezpieczeń")
                    window.show()
                time.sleep(0.25)
                continue

            if expect_player:
                try:
                    if capture_url != current_url:
                        window.run_js(capture)
                        capture_url = current_url
                    raw = window.evaluate_js(
                        "window.__CDA_FP_READ_STRUCTURED ? window.__CDA_FP_READ_STRUCTURED() : ''"
                    ) or ""
                    if not raw and time.monotonic() - started >= 1.5:
                        raw = window.evaluate_js(
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
                time.sleep(0.2)
                continue
            try:
                html = window.evaluate_js("document.documentElement ? document.documentElement.outerHTML : ''") or ""
            except Exception:
                html = ""
            if html:
                break
        else:
            raise RuntimeError("Przekroczono czas ładowania strony CDA")

        try:
            user_agent = window.evaluate_js("navigator.userAgent") or ""
        except Exception:
            user_agent = ""
        cookies = serialize_cookies(window.get_cookies())
        window.hide()
        window.set_title("CDA Free Player")
        conn.send({"type": "result", "id": request_id, "ok": True, "html": html,
                   "url": current_url, "user_agent": user_agent, "cookies": cookies})

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
        if gui == "gtk" and icon and Path(icon).exists(): kwargs["icon"] = icon
        webview.start(command_loop, gui=gui, **kwargs)
    except Exception as exc:
        conn.send({"type": "fatal", "error": "WebView: " + str(exc)})
