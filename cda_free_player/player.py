import base64
import json
import os
import re
import shutil
import socket
import struct
import subprocess
import threading
import tempfile
import sys
import uuid
from pathlib import Path
import time
import platform
import urllib.request
from datetime import datetime
from urllib.parse import urljoin, urlparse

from .client import log_event, SearchCancelled, parse_metadata, parse_comments
from .config import LOG_DIR, STATE_DIR, resource_root


class _CdpSocket:
    def __init__(self, url):
        self.url = url
        parsed = urlparse(url)
        self.sock = socket.create_connection((parsed.hostname, parsed.port), timeout=5)
        self.sock.settimeout(5)
        key = base64.b64encode(os.urandom(16)).decode()
        path = parsed.path or "/"
        if parsed.query:
            path += "?" + parsed.query
        request = (
            f"GET {path} HTTP/1.1\r\n"
            f"Host: {parsed.hostname}:{parsed.port}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n\r\n"
        ).encode()
        self.sock.sendall(request)
        data = b""
        while b"\r\n\r\n" not in data:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise RuntimeError("Chrome DevTools handshake failed")
            data += chunk
        if b" 101 " not in data.split(b"\r\n", 1)[0]:
            raise RuntimeError("Chrome DevTools handshake rejected")
        self.next_id = 1

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass

    def _read_exact(self, size):
        data = b""
        while len(data) < size:
            chunk = self.sock.recv(size - len(data))
            if not chunk:
                raise EOFError
            data += chunk
        return data

    def _send_frame(self, opcode, payload=b""):
        if isinstance(payload, str):
            payload = payload.encode()
        first = 0x80 | opcode
        length = len(payload)
        if length < 126:
            header = bytes((first, 0x80 | length))
        elif length < 65536:
            header = bytes((first, 0x80 | 126)) + struct.pack("!H", length)
        else:
            header = bytes((first, 0x80 | 127)) + struct.pack("!Q", length)
        mask = os.urandom(4)
        masked = bytes(value ^ mask[index & 3] for index, value in enumerate(payload))
        self.sock.sendall(header + mask + masked)

    def _recv_message(self):
        chunks = []
        opcode = None
        while True:
            first, second = self._read_exact(2)
            fin = bool(first & 0x80)
            current_opcode = first & 0x0F
            masked = bool(second & 0x80)
            length = second & 0x7F
            if length == 126:
                length = struct.unpack("!H", self._read_exact(2))[0]
            elif length == 127:
                length = struct.unpack("!Q", self._read_exact(8))[0]
            mask = self._read_exact(4) if masked else None
            payload = self._read_exact(length) if length else b""
            if mask:
                payload = bytes(value ^ mask[index & 3] for index, value in enumerate(payload))
            if current_opcode == 0x8:
                raise EOFError
            if current_opcode == 0x9:
                self._send_frame(0xA, payload)
                continue
            if current_opcode in (0x1, 0x2):
                opcode = current_opcode
                chunks = [payload]
            elif current_opcode == 0x0:
                chunks.append(payload)
            else:
                continue
            if fin:
                data = b"".join(chunks)
                return data.decode("utf-8", "replace") if opcode == 0x1 else data

    def call(self, method, params=None):
        message_id = self.next_id
        self.next_id += 1
        self._send_frame(0x1, json.dumps({"id": message_id, "method": method, "params": params or {}}))
        while True:
            raw = self._recv_message()
            if not isinstance(raw, str):
                continue
            message = json.loads(raw)
            if message.get("id") != message_id:
                continue
            if "error" in message:
                raise RuntimeError(message["error"].get("message", "Chrome DevTools error"))
            return message.get("result", {})


class Player:
    def __init__(self, client, db, events):
        self.client = client
        self.db = db
        self.events = events
        self.process = None
        self.monitor = None
        self.lock = threading.Lock()
        self.cancel_event = None
        self.dash_process = None
        self.dash_shell_lock = threading.Lock()
        self.dash_profile_dir = STATE_DIR / "dash-browser"

    @staticmethod
    def _normalize_optional_url(base, value):
        value = str(value or "").strip()
        if not value:
            return ""
        if value.startswith("//"):
            return "https:" + value
        return urljoin(base, value)

    @staticmethod
    def _dash_js_source():
        path = resource_root() / "assets" / "desktop" / "dash.all.min.js"
        try:
            source = path.read_text(encoding="utf-8")
        except OSError as exc:
            raise RuntimeError("Brak wbudowanego silnika DASH.") from exc
        return source.replace("</script", r"<\/script")

    @staticmethod
    def _player_js_source():
        path = resource_root() / "assets" / "desktop" / "player.js"
        try:
            source = path.read_text(encoding="utf-8")
        except OSError as exc:
            raise RuntimeError("Brak wbudowanego interfejsu odtwarzacza.") from exc
        return source.replace("</script", r"<\/script")

    @staticmethod
    def _player_icon_data_url():
        path = resource_root() / "assets" / "android" / "mipmap-mdpi" / "ic_launcher.png"
        try:
            return "data:image/png;base64," + base64.b64encode(path.read_bytes()).decode("ascii")
        except OSError:
            return ""

    def resolve(self, item, pdata, cancel_event=None):
        if not isinstance(pdata, dict) or not isinstance(pdata.get("video"), dict):
            return None

        video = pdata.get("video", {})
        dash = video.get("manifest")
        if isinstance(dash, str) and dash.strip():
            dash = "https:" + dash if dash.startswith("//") else urljoin(item["url"], dash)
            return {
                "kind": "dash-browser",
                "quality": "auto",
                "video": dash,
                "audio": None,
                "source": "player_data-dash",
                "drm_license": self._normalize_optional_url(item["url"], video.get("manifest_drm_proxy")),
                "drm_header": str(video.get("manifest_drm_header") or ""),
            }

        qualities = video.get("qualities", {})

        if isinstance(qualities, dict):
            candidates = []
            for label, value in qualities.items():
                match = re.search(r"(\d+)", str(label))
                if match:
                    candidates.append((int(match.group(1)), str(label), value))
            candidates.sort(reverse=True)

            for height, label, value in candidates:
                self.client._check_cancel(cancel_event)
                stream = self.client.post_video_get_link(item, pdata, value)
                if not stream:
                    continue
                if stream.startswith("//"):
                    stream = "https:" + stream
                clean = stream.lower().split("?", 1)[0]

                if clean.endswith((".mp4", ".m4v")):
                    return {
                        "kind": "mp4",
                        "quality": label,
                        "video": stream,
                        "audio": None,
                        "source": "videoGetLink",
                    }

                if clean.endswith(".mpd"):
                    return {
                        "kind": "dash-browser",
                        "quality": label,
                        "video": stream,
                        "audio": None,
                        "source": "videoGetLink-dash",
                        "drm_license": self._normalize_optional_url(item["url"], video.get("manifest_drm_proxy")),
                        "drm_header": str(video.get("manifest_drm_header") or ""),
                    }

                if clean.endswith(".m3u8"):
                    return {
                        "kind": "hls",
                        "quality": label,
                        "video": stream,
                        "audio": None,
                        "source": "videoGetLink",
                    }

        dash = video.get("manifest")
        if dash:
            if dash.startswith("//"):
                dash = "https:" + dash
            return {
                "kind": "dash-browser",
                "quality": "auto",
                "video": dash,
                "audio": None,
                "source": "player_data-dash",
                "drm_license": self._normalize_optional_url(item["url"], video.get("manifest_drm_proxy")),
                "drm_header": str(video.get("manifest_drm_header") or ""),
            }

        hls = video.get("manifest_apple")
        if hls:
            if hls.startswith("//"):
                hls = "https:" + hls
            return {
                "kind": "hls",
                "quality": "auto",
                "video": hls,
                "audio": None,
                "source": "player_data-hls",
            }

        direct = video.get("file")
        if direct and direct.startswith("http"):
            return {
                "kind": "mp4",
                "quality": video.get("quality", ""),
                "video": direct,
                "audio": None,
                "source": "player_data-file",
            }

        return None

    @staticmethod
    def _dash_browser_candidates(system=None):
        system = (system or platform.system()).lower()
        override = str(os.environ.get("CDA_FREE_PLAYER_BROWSER") or "").strip()
        candidates = []
        if override:
            candidates.append(override)

        if system == "darwin":
            app_roots = (Path("/Applications"), Path.home() / "Applications")
            app_bins = (
                ("Google Chrome.app", "Contents/MacOS/Google Chrome"),
                ("Microsoft Edge.app", "Contents/MacOS/Microsoft Edge"),
                ("Brave Browser.app", "Contents/MacOS/Brave Browser"),
                ("Chromium.app", "Contents/MacOS/Chromium"),
            )
            for app_name, rel in app_bins:
                for root in app_roots:
                    candidates.append(str(root / app_name / rel))
            candidates.extend(("google-chrome", "microsoft-edge", "brave-browser", "chromium"))
        elif system == "windows":
            roots = (
                os.environ.get("PROGRAMFILES(X86)"),
                os.environ.get("PROGRAMFILES"),
                os.environ.get("LOCALAPPDATA"),
            )
            relative = (
                "Microsoft/Edge/Application/msedge.exe",
                "Google/Chrome/Application/chrome.exe",
                "BraveSoftware/Brave-Browser/Application/brave.exe",
            )
            for rel in relative:
                for root in roots:
                    if root:
                        candidates.append(str(Path(root) / rel))
            candidates.extend(("msedge.exe", "chrome.exe", "brave.exe"))
        else:
            candidates.extend((
                "google-chrome-stable",
                "google-chrome",
                "microsoft-edge-stable",
                "microsoft-edge",
                "brave-browser",
                "brave-browser-stable",
                "chromium",
                "chromium-browser",
            ))

        seen = set()
        result = []
        for candidate in candidates:
            key = os.path.normcase(os.path.abspath(os.path.expanduser(candidate))) if os.path.isabs(os.path.expanduser(candidate)) else candidate.lower()
            if key in seen:
                continue
            seen.add(key)
            result.append(candidate)
        return result

    @classmethod
    def find_dash_browser(cls):
        for candidate in cls._dash_browser_candidates():
            expanded = os.path.expanduser(candidate)
            if os.path.isabs(expanded):
                if Path(expanded).is_file():
                    return str(Path(expanded))
                continue
            found = shutil.which(expanded)
            if found:
                return found
        raise RuntimeError("Do odtwarzania DASH potrzebny jest Google Chrome, Microsoft Edge albo Brave.")

    @staticmethod
    def _cdp_port(profile_dir):
        active = profile_dir / "DevToolsActivePort"
        lines = active.read_text(encoding="utf-8").splitlines()
        return int(lines[0].strip())

    @classmethod
    def _cdp_targets(cls, profile_dir):
        port = cls._cdp_port(profile_dir)
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/json/list", timeout=2) as response:
            return json.load(response)

    @classmethod
    def _wait_dash_shell_ready(cls, profile_dir, process, timeout=12.0):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline and process.poll() is None:
            try:
                port = cls._cdp_port(profile_dir)
                with urllib.request.urlopen(f"http://127.0.0.1:{port}/json/version", timeout=2) as response:
                    json.load(response)
                return port
            except Exception:
                time.sleep(0.08)
        raise RuntimeError("Nie udało się uruchomić silnika odtwarzacza.")

    @classmethod
    def _cdp_target(cls, profile_dir, process, exclude_ids=None, timeout=12.0):
        exclude_ids = set(exclude_ids or ())
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline and process.poll() is None:
            try:
                targets = cls._cdp_targets(profile_dir)
                for target in targets:
                    if target.get("id") in exclude_ids:
                        continue
                    if target.get("type") == "page" and target.get("webSocketDebuggerUrl"):
                        return target["webSocketDebuggerUrl"]
            except Exception:
                pass
            time.sleep(0.05)
        raise RuntimeError("Nie udało się otworzyć okna odtwarzacza.")

    def _browser_cookies(self):
        session = self.client.load_session() or {}
        out = []
        for cookie in session.get("cookies", []):
            name = str(cookie.get("name") or "")
            if not name:
                continue
            domain = str(cookie.get("domain") or ".cda.pl")
            if "cda.pl" not in domain:
                continue
            out.append({
                "name": name,
                "value": str(cookie.get("value") or ""),
                "domain": domain,
                "path": str(cookie.get("path") or "/"),
                "secure": bool(cookie.get("secure", True)),
            })
        return out

    @staticmethod
    def _js_value(value):
        return json.dumps(value, ensure_ascii=False).replace("</", "<\\/")

    def _dash_player_html(self, item, resolved, start_pos):
        metadata = self.db.metadata(item.get("id", "")) or {}
        cached_comments = self.db.comments(item.get("id", ""))
        full_description = str(metadata.get("description") or "").strip()
        config = self._js_value({
            "title": item.get("title", ""),
            "manifest": resolved.get("video", ""),
            "license": resolved.get("drm_license", ""),
            "drmHeader": resolved.get("drm_header", ""),
            "start": float(start_pos or 0),
            "description": full_description or str(item.get("short_description") or "").strip(),
            "descriptionReady": bool(full_description),
            "comments": cached_comments if isinstance(cached_comments, list) else [],
            "commentsReady": cached_comments is not None,
            "rating": metadata.get("rating"),
            "cdaVotes": metadata.get("cda_votes"),
        })
        dash_js = self._dash_js_source()
        player_js = self._player_js_source()
        icon = self._player_icon_data_url()
        html = """<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="theme-color" content="#000000">
<link rel="icon" href="__ICON__">
<title>CDA Free Player</title>
<style>
html,body{margin:0;width:100%;height:100%;overflow:hidden;background:#000;color:#fff;font-family:Arial,sans-serif}
#root{position:fixed;inset:0;background:#000;cursor:none}
#root.active{cursor:default}
video{position:absolute;inset:0;width:100%;height:100%;object-fit:contain;background:#000}
#loading{position:absolute;left:50%;top:50%;width:46px;height:46px;margin:-23px;border:4px solid #555;border-top-color:#fff;border-radius:50%;animation:spin .8s linear infinite;display:none;box-sizing:border-box}
#loading.show{display:block}
@keyframes spin{to{transform:rotate(360deg)}}
#error{position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);max-width:70%;padding:16px 20px;background:#151515;border-radius:8px;font-size:16px;display:none;text-align:center}
#controls{position:absolute;left:0;right:0;bottom:0;padding:52px 24px 18px;background:linear-gradient(transparent,rgba(0,0,0,.9));opacity:0;transition:opacity .18s}
#root.active #controls,#root.paused #controls{opacity:1}
#title{position:absolute;left:24px;right:150px;top:20px;font-size:18px;text-shadow:0 1px 4px #000;opacity:0;transition:opacity .18s;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
#windowControls{position:absolute;right:18px;top:14px;z-index:4;display:flex;gap:8px;opacity:0;transition:opacity .18s}
.windowButton{padding:8px 12px;border:1px solid rgba(255,255,255,.28);border-radius:7px;background:rgba(18,18,18,.72);font-size:14px;font-weight:600;backdrop-filter:blur(8px)}
.windowButton:hover,.windowButton:focus-visible{background:rgba(48,48,48,.9);border-color:rgba(255,255,255,.55);outline:none}
#root.active #title,#root.paused #title,#root.active #windowControls,#root.paused #windowControls{opacity:1}
#seek{width:100%;height:5px;margin:0 0 12px;accent-color:#fff}
.row{display:flex;align-items:center;gap:14px}
button,select,input[type=range]{font:inherit}
button{border:0;background:transparent;color:#fff;font-size:19px;padding:5px 7px;cursor:pointer}
select{background:#151515;color:#fff;border:1px solid #555;border-radius:5px;padding:5px 8px}
#time{font-variant-numeric:tabular-nums;font-size:14px;min-width:125px}
#volume{width:90px;accent-color:#fff}
.iconAction{position:relative;width:38px;height:36px;border:1px solid rgba(255,255,255,.18);border-radius:6px;background:rgba(20,20,20,.72);display:inline-flex;align-items:center;justify-content:center;padding:6px}
.iconAction:hover,.iconAction:focus-visible{background:#2d2d2d;border-color:rgba(255,255,255,.45);outline:none}
.iconAction svg{width:22px;height:22px;fill:#fff}
#contentOverlay{position:absolute;inset:0;z-index:20;background:rgba(0,0,0,.7);display:none;align-items:center;justify-content:center;padding:5vh 6vw;box-sizing:border-box;backdrop-filter:blur(4px)}
#contentOverlay.show{display:flex}
#contentCard{width:min(1120px,94vw);max-height:84vh;background:rgba(20,23,28,.97);border:1px solid rgba(255,255,255,.15);border-radius:12px;box-shadow:0 18px 70px rgba(0,0,0,.55);display:flex;flex-direction:column;overflow:hidden}
#contentHeader{height:58px;display:flex;align-items:center;padding:0 16px 0 20px;border-bottom:1px solid rgba(255,255,255,.12);flex:0 0 auto}
#contentTitle{font-size:20px;font-weight:700;flex:1}
#contentClose{font-size:27px;width:42px;height:42px;border-radius:7px}
#contentBody{overflow:auto;padding:18px 20px 24px;font-size:16px;line-height:1.5;white-space:normal}
.descriptionText{white-space:normal}
.emptyText{color:#aaa;text-align:center;padding:36px 12px}
.contentError{color:#ffb1b1;text-align:center;padding:30px 10px}
.contentLoading{display:flex;align-items:center;justify-content:center;gap:13px;padding:38px 10px;color:#ddd}
.smallSpinner{width:23px;height:23px;border:3px solid #555;border-top-color:#fff;border-radius:50%;animation:spin .8s linear infinite}
.commentsList{display:flex;flex-direction:column;gap:10px}
.comment{display:grid;grid-template-columns:48px minmax(0,1fr);gap:12px;padding:12px 14px;border-radius:8px;background:#101216;border:1px solid rgba(255,255,255,.08)}
.comment.reply{grid-template-columns:34px minmax(0,1fr);margin-left:56px;background:#171a20;border-left:3px solid rgba(85,170,255,.45)}
.commentAvatarBox{width:48px;height:48px;display:flex;align-items:center;justify-content:center;flex:0 0 auto}
.comment.reply .commentAvatarBox{width:34px;height:34px}
.commentAvatar,.commentAvatarFallback{width:48px;height:48px;border-radius:5px;object-fit:cover;background:#242831;border:1px solid rgba(255,255,255,.12);box-sizing:border-box}
.comment.reply .commentAvatar,.comment.reply .commentAvatarFallback{width:34px;height:34px}
.commentAvatarFallback{display:flex;align-items:center;justify-content:center;color:#8b929e;font-weight:700;font-size:19px}
.commentMain{min-width:0}
.commentMeta{display:flex;align-items:baseline;flex-wrap:wrap;gap:5px 10px;font-size:13px;color:#aaa;margin-bottom:5px}
.commentAuthor{color:#ff991e;font-weight:700;font-size:14px}
.commentIp{font-family:monospace;color:#9ca3ad}
.commentDate{margin-left:auto;color:#8f96a0}
.commentScore{color:#ffd22e;font-weight:700}
.commentText{white-space:normal;color:#eee;overflow-wrap:anywhere}
.spacer{flex:1}
</style>
</head>
<body>
<div id="root" class="active paused">
<video id="video" playsinline></video>
<div id="loading" class="show"></div>
<div id="error"></div>
<div id="title"></div>
<div id="windowControls"><button id="back" class="windowButton" title="Wstecz (Esc / Backspace)">← Wstecz</button></div>
<div id="controls">
<input id="seek" type="range" min="0" max="1000" value="0" step="1">
<div class="row">
<button id="play" title="Odtwórz / pauza (Spacja)">▶</button>
<span id="time">0:00 / 0:00</span>
<button id="mute" title="Wycisz / włącz dźwięk (M)">🔊</button>
<input id="volume" title="Głośność" type="range" min="0" max="1" value="1" step="0.02">
<button id="description" class="iconAction" title="Opis" aria-label="Opis"><svg viewBox="0 0 24 24"><path d="M11 17h2v-6h-2v6zM12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8zm-1-11h2V7h-2v2z"/></svg></button>
<button id="comments" class="iconAction" title="Komentarze" aria-label="Komentarze"><svg viewBox="0 0 24 24"><path d="M20 2H4C2.9 2 2 2.9 2 4v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm0 14H5.17L4 17.17V4h16v12zM7 7h10v2H7zm0 4h7v2H7z"/></svg></button>
<span class="spacer"></span>
<select id="quality" title="Jakość"><option value="auto">Auto</option></select>
<button id="full" title="Pełny ekran (F)">⛶</button>
</div>
</div>
<div id="contentOverlay">
<div id="contentCard">
<div id="contentHeader"><div id="contentTitle"></div><button id="contentClose" title="Zamknij">×</button></div>
<div id="contentBody"></div>
</div>
</div>
</div>
<script>__DASHJS__</script>
<script>
window.__CDAFP_CONFIG__=__CONFIG__;
</script>
<script>__PLAYERJS__</script>
</body>
</html>"""
        return (html.replace("__CONFIG__", config)
                    .replace("__DASHJS__", dash_js)
                    .replace("__PLAYERJS__", player_js)
                    .replace("__ICON__", icon))

    @staticmethod
    def _wait_cda_origin(cdp, process):
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline and process.poll() is None:
            try:
                result = cdp.call("Runtime.evaluate", {
                    "expression": "location.origin",
                    "returnByValue": True,
                })
                origin = result.get("result", {}).get("value")
                if origin == "https://www.cda.pl":
                    return
            except Exception:
                pass
            time.sleep(0.1)
        raise RuntimeError("Nie udało się przygotować bezpośredniego odtwarzacza CDA.")

    @staticmethod
    def _dash_browser_window_id(cdp):
        try:
            value = cdp.call("Browser.getWindowForTarget").get("windowId")
            return int(value) if value is not None else None
        except Exception:
            return None

    @staticmethod
    def _dash_browser_set_window_state(cdp, window_id, state):
        if window_id is None:
            return False
        try:
            cdp.call("Browser.setWindowBounds", {"windowId": window_id, "bounds": {"windowState": state}})
            return True
        except Exception:
            return False

    @staticmethod
    def _dash_browser_window_state(cdp, window_id):
        if window_id is None:
            return ""
        try:
            return str(cdp.call("Browser.getWindowBounds", {"windowId": window_id}).get("bounds", {}).get("windowState") or "")
        except Exception:
            return ""

    @staticmethod
    def _close_dash_page(cdp):
        try:
            parsed = urlparse(cdp.url)
            target_id = parsed.path.rsplit("/", 1)[-1]
            if not target_id:
                return
            with urllib.request.urlopen(
                f"http://{parsed.hostname}:{parsed.port}/json/close/{target_id}",
                timeout=2,
            ) as response:
                response.read()
        except Exception:
            pass

    @staticmethod
    def _request_dash_fullscreen(cdp, process, timeout=4.0):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline and process.poll() is None:
            try:
                ready = cdp.call("Runtime.evaluate", {
                    "expression": "!!(document.documentElement && window.__cdafp)",
                    "returnByValue": True,
                }).get("result", {}).get("value")
                if ready:
                    result = cdp.call("Runtime.evaluate", {
                        "expression": "(async()=>{try{if(!document.fullscreenElement)await document.documentElement.requestFullscreen();if(navigator.keyboard&&navigator.keyboard.lock){try{await navigator.keyboard.lock(['Escape']);window.__cdafp.keyboardLock=true;window.__cdafp.keyboardLockError='';}catch(e){window.__cdafp.keyboardLock=false;window.__cdafp.keyboardLockError=String(e&&e.message?e.message:e);}}return !!document.fullscreenElement;}catch(e){return false;}})()",
                        "returnByValue": True,
                        "awaitPromise": True,
                        "userGesture": True,
                    })
                    return bool(result.get("result", {}).get("value"))
            except Exception:
                pass
            time.sleep(0.08)
        return False

    def _dash_browser_command(self, browser, profile_dir):
        command = [
            browser,
            f"--user-data-dir={profile_dir}",
            "--remote-debugging-port=0",
            "--remote-debugging-address=127.0.0.1",
            "--remote-allow-origins=*",
            "--no-first-run",
            "--no-default-browser-check",
            "--disable-background-mode",
            "--disable-session-crashed-bubble",
            "--disable-extensions",
            "--disable-translate",
            "--disable-sync",
            "--disable-notifications",
            "--disable-default-apps",
            "--autoplay-policy=no-user-gesture-required",
            "--no-startup-window",
            "--log-level=3",
        ]
        if platform.system().lower() == "linux":
            command.extend(["--class=CDA-Free-Player", "--disable-features=Vulkan"])
        return command

    def _dash_app_command(self, browser, profile_dir):
        command = [
            browser,
            f"--user-data-dir={profile_dir}",
            "--no-first-run",
            "--no-default-browser-check",
            "--disable-session-crashed-bubble",
            "--disable-extensions",
            "--disable-translate",
            "--disable-sync",
            "--disable-notifications",
            "--disable-default-apps",
            "--autoplay-policy=no-user-gesture-required",
            "--start-maximized",
            "--log-level=3",
            "--app=https://www.cda.pl/robots.txt",
        ]
        if platform.system().lower() == "linux":
            command.extend(["--class=CDA-Free-Player", "--disable-features=Vulkan"])
        return command

    def _open_dash_app_window(self, browser, profile_dir, process, log_path):
        try:
            existing = {
                target.get("id")
                for target in self._cdp_targets(profile_dir)
                if target.get("type") == "page"
            }
        except Exception:
            existing = set()
        with log_path.open("a", encoding="utf-8") as log:
            launcher = subprocess.Popen(
                self._dash_app_command(browser, profile_dir),
                stdin=subprocess.DEVNULL,
                stdout=log,
                stderr=subprocess.STDOUT,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
            )
        threading.Thread(target=launcher.wait, daemon=True).start()
        return _CdpSocket(self._cdp_target(profile_dir, process, existing, timeout=8.0))

    def _dash_direct_app_command(self, browser, profile_dir):
        command = self._dash_browser_command(browser, profile_dir)
        command = [arg for arg in command if arg != "--no-startup-window"]
        command.extend(["--start-maximized", "--app=https://www.cda.pl/robots.txt"])
        return command

    def _start_dash_direct_app(self, browser, profile_dir, log_path):
        active = profile_dir / "DevToolsActivePort"
        try:
            active.unlink()
        except FileNotFoundError:
            pass
        with log_path.open("a", encoding="utf-8") as log:
            log.write("CDAFP dash-shell direct-fallback=1\n")
            log.flush()
            process = subprocess.Popen(
                self._dash_direct_app_command(browser, profile_dir),
                stdin=subprocess.DEVNULL,
                stdout=log,
                stderr=subprocess.STDOUT,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
            )
        try:
            cdp = _CdpSocket(self._cdp_target(profile_dir, process, timeout=12.0))
        except Exception:
            self._stop_process(process)
            raise
        with self.dash_shell_lock:
            self.dash_process = process
        return process, cdp

    def _configure_dash_cdp(self, cdp):
        cdp.call("Page.enable")
        cdp.call("Runtime.enable")
        cdp.call("Network.enable")
        try:
            cdp.call("Browser.setPermission", {
                "permission": {"name": "keyboardLock"},
                "setting": "granted",
                "origin": "https://www.cda.pl",
            })
        except Exception:
            try:
                cdp.call("Browser.grantPermissions", {
                    "permissions": ["keyboardLock"],
                    "origin": "https://www.cda.pl",
                })
            except Exception:
                pass

    def _ensure_dash_shell(self, log_path=None):
        with self.dash_shell_lock:
            process = self.dash_process
            if process is not None and process.poll() is None:
                return process, True

            self.dash_process = None
            browser = self.find_dash_browser()
            profile_dir = self.dash_profile_dir
            profile_dir.mkdir(parents=True, exist_ok=True)
            active = profile_dir / "DevToolsActivePort"
            try:
                active.unlink()
            except FileNotFoundError:
                pass

            target_log = log_path or (LOG_DIR / "cda-dash-browser.log")
            target_log.parent.mkdir(parents=True, exist_ok=True)
            with target_log.open("a", encoding="utf-8") as log:
                log.write(f"\nCDAFP dash-shell start browser={browser}\n")
                log.flush()
                process = subprocess.Popen(
                    self._dash_browser_command(browser, profile_dir),
                    stdin=subprocess.DEVNULL,
                    stdout=log,
                    stderr=subprocess.STDOUT,
                    creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
                )

            try:
                self._wait_dash_shell_ready(profile_dir, process)
            except Exception:
                self._stop_process(process)
                raise

            self.dash_process = process
            return process, False

    def prewarm_dash(self):
        try:
            with self.lock:
                if self.process is not None and self.process.poll() is None:
                    return
            process, reused = self._ensure_dash_shell()
            if process.poll() is None:
                log_event("dash_prewarm", reused=bool(reused), browser=self.find_dash_browser())
        except Exception as exc:
            log_event("dash_prewarm_error", error=type(exc).__name__)

    def cancel_prewarm(self):
        with self.lock:
            active_process = self.process
        with self.dash_shell_lock:
            process = self.dash_process
            if process is None or process is active_process:
                return
            self.dash_process = None
        self._stop_process(process)

    def _spawn_dash_browser(self, item, resolved, start_pos, log_path):
        process, reused = self._ensure_dash_shell(log_path)
        cdp = None
        try:
            browser = self.find_dash_browser()
            try:
                cdp = self._open_dash_app_window(browser, self.dash_profile_dir, process, log_path)
            except Exception:
                with self.dash_shell_lock:
                    if self.dash_process is process:
                        self.dash_process = None
                self._stop_process(process)
                process, cdp = self._start_dash_direct_app(browser, self.dash_profile_dir, log_path)
                reused = False
            self._configure_dash_cdp(cdp)
            cookies = self._browser_cookies()
            if cookies:
                cdp.call("Network.setCookies", {"cookies": cookies})
            cdp.call("Network.setExtraHTTPHeaders", {
                "headers": {"Referer": item["url"]},
            })
            try:
                self._wait_cda_origin(cdp, process)
            except RuntimeError:
                cdp.call("Page.navigate", {"url": "https://www.cda.pl/robots.txt"})
                self._wait_cda_origin(cdp, process)
            frame_tree = cdp.call("Page.getFrameTree")
            frame_id = frame_tree["frameTree"]["frame"]["id"]
            cdp.call("Page.setDocumentContent", {
                "frameId": frame_id,
                "html": self._dash_player_html(item, resolved, start_pos),
            })
            window_id = self._dash_browser_window_id(cdp)

            self._dash_browser_set_window_state(cdp, window_id, "normal")
            self._dash_browser_set_window_state(cdp, window_id, "maximized")
            try:
                cdp.call("Page.bringToFront")
            except Exception:
                pass
            fullscreen = self._request_dash_fullscreen(cdp, process, timeout=1.5)
            if not fullscreen:
                try:
                    cdp.call("Page.bringToFront")
                except Exception:
                    pass
                fullscreen = self._request_dash_fullscreen(cdp, process)
            try:
                cdp.call("Page.bringToFront")
            except Exception:
                pass

            with log_path.open("a", encoding="utf-8") as log:
                log.write(
                    f"\nCDAFP dash-browser source={resolved.get('source', '')} "
                    f"start={start_pos:.3f} browser={self.find_dash_browser()} "
                    f"manifest={resolved.get('video', '')} reused={int(reused)}\n"
                )
                log.write(
                    f"CDAFP dash-shell fullscreen={int(fullscreen)} "
                    f"window_id={window_id if window_id is not None else 'none'}\n"
                )
            return process, cdp, window_id
        except Exception:
            if cdp is not None:
                cdp.close()
            with self.dash_shell_lock:
                if self.dash_process is process:
                    self.dash_process = None
            self._stop_process(process)
            raise

    @staticmethod
    def _dash_browser_state(cdp):
        script = """(() => {
            const v = document.getElementById('video');
            const s = window.__cdafp || {};
            if (!v) return {found:false,error:s.error||''};
            return {
                found:true,
                ready:!!s.ready,
                position:Number(v.currentTime)||0,
                duration:Number(v.duration)||0,
                paused:!!v.paused,
                error:String(s.error||''),
                quality:String(s.quality||'auto'),
                closeRequested:!!s.closeRequested,
                fullscreen:!!s.fullscreen,
                keyboardLock:!!s.keyboardLock,
                keyboardLockError:String(s.keyboardLockError||''),
                contentRequest:String(s.contentRequest||'')
            };
        })()"""
        result = cdp.call("Runtime.evaluate", {
            "expression": script,
            "returnByValue": True,
            "awaitPromise": False,
        })
        return result.get("result", {}).get("value") or {"found": False}

    @staticmethod
    def _merge_metadata(existing, fresh):
        merged = dict(existing or {})
        for key, value in (fresh or {}).items():
            if value is None or value == "":
                continue
            merged[key] = value
        return merged

    def _load_dash_content(self, item, kind):
        vid = item.get("id", "")
        metadata = self.db.metadata(vid) or {}
        if kind == "description":
            description = str(metadata.get("description") or "").strip()
            if description:
                return {"ok": True, "description": description, "rating": metadata.get("rating"), "cdaVotes": metadata.get("cda_votes")}
        elif kind == "comments":
            cached = self.db.comments(vid)
            if cached is not None:
                return {"ok": True, "comments": cached, "rating": metadata.get("rating"), "cdaVotes": metadata.get("cda_votes")}
        if kind == "comments":
            text, source = self.client.get_comments_html(item["url"])
            comments = parse_comments(text)
        else:
            text, source = self.client.get_html(item["url"], True)
            comments = []
        fresh, _ = parse_metadata(text)
        if kind == "comments" and fresh.get("comment_count") is None:
            fresh["comment_count"] = len(comments)
        merged = self._merge_metadata(metadata, fresh)
        if not merged.get("description"):
            merged["description"] = str(item.get("short_description") or "").strip()
        self.db.save_metadata(vid, merged)
        if kind == "comments":
            self.db.save_comments(vid, comments)
        log_event("player_content", id=vid, kind=kind, source=source, comments=len(comments))
        if kind == "description":
            return {"ok": True, "description": merged.get("description", ""), "rating": merged.get("rating"), "cdaVotes": merged.get("cda_votes")}
        return {"ok": True, "comments": comments, "rating": merged.get("rating"), "cdaVotes": merged.get("cda_votes")}

    @staticmethod
    def _deliver_dash_content(cdp, kind, payload):
        expression = "window.__cdafpDeliverContent && window.__cdafpDeliverContent(%s,%s)" % (
            json.dumps(kind, ensure_ascii=False),
            json.dumps(payload, ensure_ascii=False),
        )
        cdp.call("Runtime.evaluate", {"expression": expression})

    def _monitor_dash_browser(self, process, cdp, window_id, item, position, duration, log_path, cancel_event):
        last_save = 0.0
        last_error = ""
        ready_logged = False
        keyboard_logged = False
        started_at = time.monotonic()
        try:
            while process.poll() is None:
                self.client._check_cancel(cancel_event)
                state = self._dash_browser_state(cdp)
                if state.get("found"):
                    value = state.get("position")
                    total = state.get("duration")
                    if isinstance(value, (int, float)) and value >= 0:
                        position = value
                    if isinstance(total, (int, float)) and total > 0:
                        duration = total
                    if state.get("ready") and not ready_logged:
                        ready_logged = True
                        with log_path.open("a", encoding="utf-8") as log:
                            log.write(
                                f"CDAFP dash-ready startup={time.monotonic() - started_at:.3f}s "
                                f"position={position:.3f} duration={duration:.3f}\n"
                            )
                    if state.get("ready") and not keyboard_logged:
                        keyboard_logged = True
                        with log_path.open("a", encoding="utf-8") as log:
                            log.write(
                                f"CDAFP keyboard-lock active={int(bool(state.get('keyboardLock')))} "
                                f"error={str(state.get('keyboardLockError') or '')[:240]}\n"
                            )
                    content_request = str(state.get("contentRequest") or "")
                    if content_request in ("description", "comments"):
                        try:
                            cdp.call("Runtime.evaluate", {"expression": "window.__cdafp.contentRequest=''"})
                            payload = self._load_dash_content(item, content_request)
                        except Exception as exc:
                            payload = {"ok": False, "error": f"Nie udało się wczytać danych: {exc}"}
                            log_event("player_content_error", id=item["id"], kind=content_request, error=type(exc).__name__)
                        try:
                            self._deliver_dash_content(cdp, content_request, payload)
                        except Exception:
                            pass
                    error = str(state.get("error") or "")
                    if error and error != last_error:
                        last_error = error
                        with log_path.open("a", encoding="utf-8") as log:
                            log.write(f"CDAFP dash-error {error}\n")
                    if state.get("closeRequested"):
                        break
                    now = time.monotonic()
                    if duration > 0 and now - last_save >= 3:
                        self.db.save_history(item, position, duration)
                        last_save = now
                time.sleep(0.2)
        except (SearchCancelled, EOFError, OSError):
            pass
        except Exception as exc:
            log_event("dash_player_error", id=item["id"], error=type(exc).__name__)
            with log_path.open("a", encoding="utf-8") as log:
                log.write(f"CDAFP dash-monitor-error {type(exc).__name__}: {exc}\n")
        finally:
            if duration > 0:
                self.db.save_history(item, position, duration)
            self._close_dash_page(cdp)
            cdp.close()
            with self.lock:
                if self.process is process:
                    self.process = None
            if process.poll() is not None:
                with self.dash_shell_lock:
                    if self.dash_process is process:
                        self.dash_process = None
            else:
                with log_path.open("a", encoding="utf-8") as log:
                    log.write("CDAFP dash-shell idle=1\n")
            self.events.put(("play_end", item["id"]))
            log_event("play_end", id=item["id"], position=position, duration=duration, log=str(log_path))

    @staticmethod
    def find_mpv():
        name = "mpv.exe" if os.name == "nt" else "mpv"
        for path in (
            Path(sys.executable).resolve().parent / "mpv" / name,
            resource_root() / "mpv" / name,
            Path("/opt/homebrew/bin/mpv"),
            Path("/usr/local/bin/mpv"),
        ):
            if path.is_file():
                return str(path)
        found = shutil.which(name)
        if found:
            return found
        raise RuntimeError(
            "Brak mpv. Windows: rozpakuj całą paczkę z katalogiem mpv. "
            "macOS: brew install mpv. Linux: sudo apt install mpv."
        )

    @staticmethod
    def _visible_log_dir():
        for path in (Path.home() / "Pobrane", Path.home() / "Downloads"):
            if path.is_dir():
                return path
        return LOG_DIR

    def _spawn(self, mpv, item, resolved, start_pos, log_path):
        stream = resolved["video"]
        audio_stream = resolved.get("audio")
        kind = resolved["kind"]

        ipc_name = "cdafp-" + uuid.uuid4().hex
        ipc = "\\\\.\\pipe\\" + ipc_name if os.name == "nt" else str(Path(tempfile.gettempdir()) / (ipc_name + ".sock"))

        session = self.client.load_session()
        headers = [f"Referer: {item['url']}"]
        if session:
            headers.append(f"User-Agent: {session['user_agent']}")
            cookie = "; ".join(
                f"{c['name']}={c['value']}"
                for c in session.get("cookies", [])
                if c.get("name") is not None and c.get("value") is not None
            )
            if cookie:
                headers.append(f"Cookie: {cookie}")

        command = [
            mpv,
            stream,
            "--fs",
            "--force-window=yes",
            "--ytdl=no",
            "--hwdec=auto-safe",
            "--cache=yes",
            "--cache-secs=15",
            "--demuxer-readahead-secs=12",
            f"--input-ipc-server={ipc}",
            f"--title={item['title']}",
            f"--http-header-fields={','.join(headers)}",
        ]

        if kind == "mp4-range":
            command.extend(["--demuxer-lavf-o=seekable=1", "--cache-secs=8"])
        if audio_stream:
            command.append(f"--audio-file={audio_stream}")
        if start_pos > 5:
            command.append(f"--start={start_pos}")
        if kind == "hls":
            command.append("--hls-bitrate=max")

        with log_path.open("a", encoding="utf-8") as log:
            log.write(
                f"\nCDAFP v7-path source={resolved.get('source', '')} kind={kind} "
                f"quality={resolved.get('quality', '')} start={start_pos:.3f} "
                f"separate_audio={bool(audio_stream)}\n"
            )
            log.flush()
            process = subprocess.Popen(
                command,
                stdin=subprocess.DEVNULL,
                stdout=log,
                stderr=subprocess.STDOUT,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
            )
        return process, ipc

    def play(self, item, pdata, cancel_event=None):
        cancel_event = cancel_event or threading.Event()
        self.client._check_cancel(cancel_event)
        resolved = self.resolve(item, pdata, cancel_event)
        if not resolved:
            raise RuntimeError("Nie udało się pobrać strumienia filmu.")

        start_pos, media_duration = self.db.history_position(item["id"])
        if media_duration and start_pos >= media_duration * 0.95:
            start_pos = 0

        log_dir = self._visible_log_dir()
        log_dir.mkdir(parents=True, exist_ok=True)
        log_path = log_dir / f"CDA-Free-Player-player-{datetime.now():%Y%m%d-%H%M%S}.log"

        if resolved["kind"] == "dash-browser":
            with self.lock:
                self.client._check_cancel(cancel_event)
                if self.process is not None and self.process.poll() is None:
                    raise RuntimeError("Film jest już odtwarzany. Zamknij jego okno przed otwarciem następnego.")
                process, cdp, window_id = self._spawn_dash_browser(item, resolved, start_pos, log_path)
                self.process = process
                self.cancel_event = cancel_event
                self.monitor = threading.Thread(
                    target=self._monitor_dash_browser,
                    args=(process, cdp, window_id, item, start_pos, media_duration, log_path, cancel_event),
                    daemon=True,
                )
                self.monitor.start()
            self.db.save_history(item, start_pos, media_duration)
            log_event(
                "play",
                id=item["id"],
                stream_type="dash-browser",
                quality=resolved.get("quality", "auto"),
                resolve_source=resolved.get("source", "player_data-dash"),
                separate_audio=False,
                resume=start_pos,
                log=str(log_path),
            )
            return "dash", resolved.get("quality", "auto"), log_path

        mpv = self.find_mpv()

        with self.lock:
            self.client._check_cancel(cancel_event)
            if self.process is not None and self.process.poll() is None:
                raise RuntimeError("Film jest już odtwarzany. Zamknij jego okno przed otwarciem następnego.")
            process, ipc = self._spawn(mpv, item, resolved, start_pos, log_path)
            self.process = process
            self.cancel_event = cancel_event
            self.monitor = threading.Thread(
                target=self._monitor,
                args=(process, ipc, item, start_pos, media_duration, log_path, cancel_event),
                daemon=True,
            )
            self.monitor.start()

        self.db.save_history(item, start_pos, media_duration)
        log_event(
            "play",
            id=item["id"],
            stream_type=resolved["kind"],
            quality=resolved.get("quality", ""),
            resolve_source=resolved.get("source", ""),
            separate_audio=bool(resolved.get("audio")),
            resume=start_pos,
            log=str(log_path),
        )
        return resolved["kind"], resolved.get("quality", ""), log_path

    def _connect_ipc(self, process, ipc, cancel_event):
        deadline = time.monotonic() + 15
        while process.poll() is None and time.monotonic() < deadline:
            self.client._check_cancel(cancel_event)
            try:
                if os.name == "nt":
                    return open(ipc, "r+b", buffering=0)
                conn = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                conn.settimeout(0.25)
                conn.connect(ipc)
                return conn
            except OSError:
                try:
                    conn.close()
                except Exception:
                    pass
                time.sleep(0.1)
        return None

    def _monitor(self, process, ipc, item, position, duration, log_path, cancel_event):
        conn = None
        ready = False
        last_save = 0.0
        buffer = b""
        try:
            conn = self._connect_ipc(process, ipc, cancel_event)
            if conn is None:
                if process.poll() is None and not cancel_event.is_set():
                    self.events.put(("error", f"Odtwarzacz nie udostępnił zapisu postępu. Log: {log_path}"))
                return

            commands = b"".join(
                (json.dumps({"command": ["observe_property", i, name]}) + "\n").encode()
                for i, name in enumerate(("time-pos", "duration"), 1)
            )

            if os.name == "nt":
                import ctypes
                import msvcrt
                from ctypes import wintypes
                peek = ctypes.WinDLL("kernel32", use_last_error=True).PeekNamedPipe
                peek.argtypes = [
                    wintypes.HANDLE, wintypes.LPVOID, wintypes.DWORD,
                    ctypes.POINTER(wintypes.DWORD), ctypes.POINTER(wintypes.DWORD),
                    ctypes.POINTER(wintypes.DWORD),
                ]
                peek.restype = wintypes.BOOL
                handle = msvcrt.get_osfhandle(conn.fileno())
                conn.write(commands)
            else:
                conn.sendall(commands)

            while process.poll() is None:
                self.client._check_cancel(cancel_event)
                if os.name == "nt":
                    available = wintypes.DWORD()
                    if not peek(handle, None, 0, None, ctypes.byref(available), None):
                        break
                    if not available.value:
                        time.sleep(0.1)
                        continue
                    chunk = conn.read(min(65536, available.value))
                else:
                    try:
                        chunk = conn.recv(65536)
                    except socket.timeout:
                        continue

                if not chunk:
                    break
                buffer += chunk
                while b"\n" in buffer:
                    line, buffer = buffer.split(b"\n", 1)
                    try:
                        event = json.loads(line)
                    except (ValueError, UnicodeError):
                        continue
                    if event.get("event") == "property-change":
                        value = event.get("data")
                        if isinstance(value, (int, float)) and value >= 0:
                            if event.get("name") == "time-pos":
                                position = value
                                ready = True
                            elif event.get("name") == "duration":
                                duration = value
                    now = time.monotonic()
                    if ready and duration > 0 and now - last_save >= 5:
                        self.db.save_history(item, position, duration)
                        last_save = now
        except SearchCancelled:
            pass
        except (OSError, ValueError) as exc:
            log_event("player_ipc_error", id=item["id"], error=type(exc).__name__)
        finally:
            if conn is not None:
                try:
                    conn.close()
                except Exception:
                    pass
            if ready and duration > 0:
                self.db.save_history(item, position, duration)
            if os.name != "nt":
                try:
                    os.unlink(ipc)
                except FileNotFoundError:
                    pass
            with self.lock:
                if self.process is process:
                    self.process = None
            if process.poll() not in (None, 0) and not cancel_event.is_set():
                self.events.put(("error", f"Odtwarzacz zakończył się błędem. Log: {log_path}"))
            self.events.put(("play_end", item["id"]))
            log_event("play_end", id=item["id"], position=position, duration=duration, log=str(log_path))

    @staticmethod
    def _stop_process(process):
        if process is None or process.poll() is not None:
            return
        process.terminate()
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            process.kill()

    def stop(self):
        with self.lock:
            process = self.process
            if self.cancel_event is not None:
                self.cancel_event.set()
        with self.dash_shell_lock:
            dash_process = self.dash_process
            self.dash_process = None
        if process is not None and process is not dash_process:
            self._stop_process(process)
        self._stop_process(dash_process)
        if self.monitor is not None:
            self.monitor.join(timeout=2)
