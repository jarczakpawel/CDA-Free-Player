import json
import os
import threading
import time
from pathlib import Path


def is_challenge(html):
    value = (html or "").lower()
    return (
        "przeprowadzanie weryfikacji zabezpieczeń" in value
        or "checking if you are not a bot" in value
        or "/cdn-cgi/challenge-platform/" in value
        or "cf-chl-" in value
        or "just a moment..." in value
        or "verify you are human" in value
    )


def serialize_cookies(cookies):
    out = []
    for cookie in cookies or []:
        try:
            for name, morsel in cookie.items():
                out.append({
                    "name": str(name),
                    "value": str(morsel.value),
                    "domain": str(morsel["domain"] or ".cda.pl"),
                    "path": str(morsel["path"] or "/"),
                })
        except Exception:
            pass
    return out


def run_webview_worker(conn, storage, icon, gui):
    try:
        import webview
    except Exception as exc:
        conn.send({"type": "fatal", "error": f"pywebview import: {exc}"})
        return

    Path(storage).mkdir(parents=True, exist_ok=True)
    window = webview.create_window(
        "CDA Free Player",
        "about:blank",
        width=980,
        height=720,
        min_size=(640, 480),
        hidden=True,
        focus=True,
        background_color="#101216",
        text_select=True,
    )
    cancel = threading.Event()

    def page_html():
        try:
            return window.evaluate_js(
                "(function(){return document.documentElement ? "
                "document.documentElement.outerHTML : '';})()"
            ) or ""
        except Exception:
            return ""

    def current_url():
        try: return window.get_current_url() or ""
        except Exception: return ""

    def user_agent():
        try: return window.evaluate_js("navigator.userAgent") or ""
        except Exception: return ""

    def fetch(req):
        request_id = req.get("id")
        url = req["url"]
        cancel.clear()
        conn.send({"type":"event","event":"loading","id":request_id,"url":url})
        try: window.hide()
        except Exception: pass
        try: window.load_url(url)
        except Exception as exc:
            conn.send({"type":"result","id":request_id,"ok":False,"error":str(exc)})
            return

        deadline = time.monotonic() + 180
        first_seen = time.monotonic()
        interactive_shown = False
        stable_non_challenge = 0
        last_html = ""
        while time.monotonic() < deadline:
            if cancel.is_set():
                try:
                    window.hide(); window.load_url("about:blank")
                except Exception: pass
                conn.send({"type":"result","id":request_id,"ok":False,"cancelled":True})
                return
            time.sleep(0.18)
            html = page_html()
            if not html: continue
            last_html = html
            if is_challenge(html):
                stable_non_challenge = 0
                if not interactive_shown and time.monotonic() - first_seen >= 2.2:
                    interactive_shown = True
                    conn.send({"type":"event","event":"interactive","id":request_id,"url":current_url()})
                    try:
                        window.title = "CDA Free Player — Weryfikacja zabezpieczeń"
                        window.show()
                    except Exception: pass
                continue
            stable_non_challenge += 1
            if stable_non_challenge < 2: continue
            try:
                window.hide(); window.title = "CDA Free Player"
            except Exception: pass
            conn.send({
                "type":"result", "id":request_id, "ok":True,
                "html":html, "url":current_url(), "user_agent":user_agent(),
                "cookies":serialize_cookies(window.get_cookies()),
                "interactive":interactive_shown,
            })
            return
        try: window.hide()
        except Exception: pass
        conn.send({"type":"result","id":request_id,"ok":False,"error":"Timeout weryfikacji WebView","html":last_html[:1000]})

    def command_loop():
        conn.send({"type":"ready"})
        while True:
            try: req = conn.recv()
            except (EOFError, OSError):
                try: window.destroy()
                except Exception: pass
                return
            cmd = req.get("cmd")
            if cmd == "fetch": fetch(req)
            elif cmd == "cancel": cancel.set()
            elif cmd == "quit":
                try: window.destroy()
                except Exception: pass
                return

    kwargs = dict(private_mode=False, storage_path=storage, debug=False)
    if icon and Path(icon).exists(): kwargs["icon"] = icon
    try:
        webview.start(command_loop, gui=gui or None, **kwargs)
    except Exception as exc:
        try: conn.send({"type":"fatal","error":f"webview start: {exc}"})
        except Exception: pass
