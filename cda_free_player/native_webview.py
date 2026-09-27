import multiprocessing as mp
import platform
import threading
import time
import uuid

from .config import APP_ICON, ASSET_DIR, WEBVIEW_STORAGE_DIR
from .client import log_event, SearchCancelled
from .webview_worker import run_webview_worker


class NativeWebViewUnavailable(RuntimeError):
    pass


class NativeWebViewSession:
    def __init__(self, events):
        self.events = events
        self.proc = None
        self.conn = None
        self.lock = threading.Lock()

    def _gui(self):
        return {"linux": "gtk", "windows": "edgechromium", "darwin": "cocoa"}.get(platform.system().lower())

    def _start(self):
        if self.proc is not None and self.proc.is_alive():
            return
        started = time.monotonic()
        self.stop()
        ctx = mp.get_context("spawn")
        parent, child = ctx.Pipe(duplex=True)
        self.conn = parent
        self.proc = ctx.Process(
            target=run_webview_worker,
            args=(child, str(WEBVIEW_STORAGE_DIR), str(APP_ICON), self._gui(), str(ASSET_DIR / "web" / "player_capture.js")),
            daemon=True,
            name="cda-free-player-webview",
        )
        self.proc.start()
        child.close()
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            if not self.proc.is_alive():
                raise NativeWebViewUnavailable("Native WebView zakończył się podczas startu")
            if self.conn.poll(0.2):
                msg = self.conn.recv()
                if msg.get("type") == "ready":
                    log_event("native_webview_ready", ms=round((time.monotonic() - started) * 1000))
                    return
                if msg.get("type") == "fatal":
                    raise NativeWebViewUnavailable(msg.get("error", "WebView fatal"))
        raise NativeWebViewUnavailable("Timeout uruchomienia native WebView")

    def _send(self, payload):
        if self.conn is None or self.proc is None or not self.proc.is_alive():
            raise NativeWebViewUnavailable("Native WebView nie działa")
        self.conn.send(payload)

    def fetch(self, url, cancel_event=None, expect_player=False, expand_comments=False):
        with self.lock:
            started = time.monotonic()
            interactive_logged = False
            if cancel_event is not None and cancel_event.is_set():
                raise SearchCancelled("Wyszukiwanie anulowane.")
            self._start()
            request_id = uuid.uuid4().hex
            self._send({"cmd":"fetch","id":request_id,"url":url,"expect_player":expect_player,"expand_comments":expand_comments})
            deadline = time.monotonic() + 185
            while True:
                if time.monotonic() > deadline:
                    self.stop()
                    raise NativeWebViewUnavailable("Przekroczono czas odpowiedzi WebView")
                if cancel_event is not None and cancel_event.is_set():
                    try: self._send({"cmd":"cancel","id":request_id})
                    except Exception: pass
                    self.stop()
                    raise SearchCancelled("Wyszukiwanie anulowane.")
                if self.proc is None or not self.proc.is_alive():
                    raise NativeWebViewUnavailable("Native WebView zakończył pracę")
                if not self.conn.poll(0.15):
                    continue
                msg = self.conn.recv()
                if msg.get("id") not in (None, request_id):
                    continue
                if msg.get("type") == "event":
                    event = msg.get("event")
                    if event == "loading":
                        self.events.put(("security_verification","native_hidden",url))
                    elif event == "interactive":
                        self.events.put(("security_verification","native_interactive",url))
                        if not interactive_logged:
                            interactive_logged = True
                            log_event("native_webview_challenge", url=url, ms=round((time.monotonic() - started) * 1000))
                    continue
                if msg.get("type") == "fatal":
                    raise NativeWebViewUnavailable(msg.get("error", "WebView fatal"))
                if msg.get("type") == "result":
                    if msg.get("cancelled"):
                        raise SearchCancelled("Wyszukiwanie anulowane.")
                    if not msg.get("ok"):
                        raise RuntimeError(msg.get("error", "WebView error"))
                    result = msg
                    log_event("native_webview_result", url=url, ms=round((time.monotonic() - started) * 1000), interactive=interactive_logged)
                    self.stop()
                    return result

    def stop(self):
        proc, conn = self.proc, self.conn
        self.proc = None
        self.conn = None
        if conn is not None:
            try: conn.send({"cmd":"quit"})
            except Exception: pass
        if proc is not None:
            try:
                proc.join(2)
                if proc.is_alive(): proc.terminate(); proc.join(2)
                if proc.is_alive(): proc.kill(); proc.join(1)
            except Exception: pass
        if conn is not None:
            try: conn.close()
            except Exception: pass
