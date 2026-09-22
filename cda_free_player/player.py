import json
import os
import re
import shutil
import socket
import subprocess
import threading
import tempfile
import sys
import uuid
from pathlib import Path
import time
import xml.etree.ElementTree as ET
from datetime import datetime
from urllib.parse import urljoin

from .client import log_event, SearchCancelled
from .config import LOG_DIR, resource_root


class Player:
    def __init__(self, client, db, events):
        self.client = client
        self.db = db
        self.events = events
        self.process = None
        self.monitor = None
        self.lock = threading.Lock()

    def resolve(self, item, pdata):
        if not isinstance(pdata, dict) or not isinstance(pdata.get("video"), dict):
            return None
        video = pdata["video"]

        # Desktop mpv/FFmpeg seeks most reliably when CDA gives us the concrete
        # quality MP4 files (video + optional audio) behind a static MPD.  This
        # is the path that historically gave the desktop build fast HTTP Range
        # seeking.  Do not start with the adaptive player_data MPD: it can play
        # correctly but FFmpeg may lose A/V sync after a long seek.
        dash_fallbacks = []
        hls_fallbacks = []

        qualities = video.get("qualities", {})
        if isinstance(qualities, dict):
            candidates = []
            for label, value in qualities.items():
                match = re.search(r"(\d+)", str(label))
                candidates.append((int(match.group(1)) if match else 0, str(label), value))
            candidates.sort(reverse=True)

            for height, label, value in candidates:
                stream = self.client.post_video_get_link(item, pdata, value)
                if not stream:
                    continue
                stream = self._normalize_stream_url(item["url"], stream)
                if not stream:
                    continue
                clean = stream.lower().split("?", 1)[0]

                if clean.endswith((".mp4", ".m4v")):
                    return {
                        "kind": "mp4",
                        "quality": label,
                        "video": stream,
                        "audio": None,
                        "source": "videoGetLink-direct",
                    }

                if clean.endswith(".mpd"):
                    tracks = self._mpd_tracks(item, stream, height)
                    if tracks:
                        tracks["quality"] = label or tracks.get("quality", "auto")
                        tracks["source"] = "videoGetLink-mp4-range"
                        return tracks
                    dash_fallbacks.append({
                        "kind": "dash",
                        "quality": label or f"{height}p",
                        "video": stream,
                        "audio": None,
                        "source": "videoGetLink-dash-fallback",
                    })
                    continue

                if clean.endswith(".m3u8"):
                    selected = self._hls_candidate(item, stream)
                    if selected:
                        hls_fallbacks.append({
                            "kind": "hls",
                            "quality": label,
                            "video": selected,
                            "audio": None,
                            "source": "videoGetLink-hls-fallback",
                        })
                    continue

                if stream.startswith(("http://", "https://")):
                    return {
                        "kind": "mp4",
                        "quality": label,
                        "video": stream,
                        "audio": None,
                        "source": "videoGetLink-direct",
                    }

        # A direct file is also preferable to an adaptive manifest, but keep it
        # after the quality resolver so we do not accidentally choose a lower
        # quality generic file when CDA exposes a higher concrete quality.
        direct = self._normalize_stream_url(item["url"], video.get("file"))
        if direct and direct.startswith(("http://", "https://")):
            return {
                "kind": "mp4",
                "quality": video.get("quality", ""),
                "video": direct,
                "audio": None,
                "source": "player_data-file",
            }

        # Try to reduce the player_data DASH manifest to static MP4 Range files.
        # Only if that is impossible keep the whole MPD as a last-resort DASH
        # source.  This preserves playback for manifests which Media3 handles on
        # Android while avoiding the FFmpeg DASH seek path whenever possible.
        dash = self._normalize_stream_url(item["url"], video.get("manifest"))
        if dash:
            tracks = self._mpd_tracks(item, dash, None)
            if tracks:
                tracks["quality"] = tracks.get("quality", "auto-max")
                tracks["source"] = "player_data-mp4-range"
                return tracks
            dash_fallbacks.append({
                "kind": "dash",
                "quality": "auto",
                "video": dash,
                "audio": None,
                "source": "player_data-dash-fallback",
            })

        hls = self._normalize_stream_url(item["url"], video.get("manifest_apple"))
        if hls:
            selected = self._hls_candidate(item, hls)
            if selected:
                hls_fallbacks.append({
                    "kind": "hls",
                    "quality": "auto",
                    "video": selected,
                    "audio": None,
                    "source": "player_data-hls-fallback",
                })

        # A whole DASH MPD is still a better fallback than Apple HLS on desktop
        # for the CDA pages seen in testing.  Crucially, we reach this only when
        # no seek-friendly concrete MP4/Range source exists.
        if dash_fallbacks:
            return dash_fallbacks[0]
        if hls_fallbacks:
            return hls_fallbacks[0]
        return None

    @staticmethod
    def _normalize_stream_url(base_url, value):
        if not isinstance(value, str):
            return ""
        value = value.strip()
        if not value:
            return ""
        if value.startswith("//"):
            return "https:" + value
        return urljoin(base_url, value)

    def _hls_candidate(self, item, manifest_url):
        client = self.client.http_client()
        if not client:
            return manifest_url
        try:
            response = client.get(manifest_url, headers={"Referer": item["url"]})
            response.raise_for_status()
            text = response.text
            final_url = str(response.url)
            variants = []
            lines = [line.strip() for line in text.splitlines()]
            for index, line in enumerate(lines):
                if not line.upper().startswith("#EXT-X-STREAM-INF:"):
                    continue
                match = re.search(r"(?:AVERAGE-)?BANDWIDTH=(\d+)", line, re.I)
                bandwidth = int(match.group(1)) if match else 0
                for uri in lines[index + 1:]:
                    if not uri or uri.startswith("#"):
                        continue
                    variants.append((bandwidth, urljoin(final_url, uri)))
                    break
            if not variants:
                return final_url
            variants.sort(key=lambda entry: entry[0], reverse=True)
            return variants[0][1]
        except Exception as exc:
            log_event("hls_probe_error", id=item["id"], error=type(exc).__name__)
            return manifest_url
        finally:
            client.close()

    def _mpd_tracks(
        self,
        item,
        manifest_url,
        preferred_height,
    ):
        client = self.client.http_client()

        if not client:
            return None

        try:
            response = client.get(
                manifest_url,
                headers={
                    "Referer": item["url"],
                },
            )
            response.raise_for_status()

            root = ET.fromstring(
                response.content
            )

            if root.findall(".//{*}SegmentTemplate") or root.findall(".//{*}SegmentList") or len(root.findall("{*}Period")) != 1:
                return None
            parents = {child: parent for parent in root.iter() for child in parent}
            video_tracks = []
            audio_tracks = []

            for adaptation in root.findall(
                ".//{*}AdaptationSet"
            ):
                adaptation_type = (
                    adaptation.get(
                        "contentType",
                        "",
                    )
                    or adaptation.get(
                        "mimeType",
                        "",
                    )
                ).lower()

                adaptation_base = adaptation.find(
                    "{*}BaseURL"
                )

                adaptation_base_text = (
                    adaptation_base.text.strip()
                    if (
                        adaptation_base is not None
                        and adaptation_base.text
                    )
                    else None
                )

                for representation in adaptation.findall(
                    "{*}Representation"
                ):
                    mime = (
                        representation.get(
                            "mimeType",
                            "",
                        )
                        or adaptation.get(
                            "mimeType",
                            "",
                        )
                    ).lower()

                    content_type = (
                        representation.get(
                            "contentType",
                            "",
                        )
                        or adaptation_type
                    ).lower()

                    codecs = (
                        representation.get(
                            "codecs",
                            "",
                        )
                    ).lower()

                    base = representation.find(
                        "{*}BaseURL"
                    )

                    base_text = (
                        base.text.strip()
                        if (
                            base is not None
                            and base.text
                        )
                        else adaptation_base_text
                    )

                    if not base_text:
                        continue

                    chain = []
                    node = representation
                    while node is not None:
                        chain.append(node)
                        node = parents.get(node)
                    url = str(response.url)
                    for node in reversed(chain):
                        base_node = node.find("{*}BaseURL")
                        if base_node is not None and base_node.text:
                            url = urljoin(url, base_node.text.strip())
                    if url.split("?", 1)[0].endswith("/"):
                        continue

                    height = int(
                        representation.get(
                            "height",
                            "0",
                        )
                        or 0
                    )

                    bandwidth = int(
                        representation.get(
                            "bandwidth",
                            "0",
                        )
                        or 0
                    )

                    entry = {
                        "url": url,
                        "height": height,
                        "bandwidth": bandwidth,
                    }

                    is_audio = (
                        "audio" in content_type
                        or "audio" in mime
                        or (
                            not height
                            and (
                                codecs.startswith(
                                    "mp4a"
                                )
                                or codecs.startswith(
                                    "aac"
                                )
                            )
                        )
                    )

                    is_video = (
                        "video" in content_type
                        or "video" in mime
                        or height > 0
                    )

                    if is_audio:
                        audio_tracks.append(
                            entry
                        )
                    elif is_video:
                        video_tracks.append(
                            entry
                        )

            if not video_tracks:
                return None

            if preferred_height:
                exact = [
                    track
                    for track in video_tracks
                    if track["height"]
                    == preferred_height
                ]

                if exact:
                    video_track = max(
                        exact,
                        key=lambda track: (
                            track["bandwidth"]
                        ),
                    )
                else:
                    below = [
                        track
                        for track in video_tracks
                        if (
                            track["height"]
                            and track["height"]
                            <= preferred_height
                        )
                    ]

                    pool = (
                        below
                        if below
                        else video_tracks
                    )

                    video_track = max(
                        pool,
                        key=lambda track: (
                            track["height"],
                            track["bandwidth"],
                        ),
                    )
            else:
                video_track = max(
                    video_tracks,
                    key=lambda track: (
                        track["height"],
                        track["bandwidth"],
                    ),
                )

            audio_track = (
                max(
                    audio_tracks,
                    key=lambda track: (
                        track["bandwidth"],
                    ),
                )
                if audio_tracks
                else None
            )

            log_event(
                "mpd_direct_tracks",
                id=item["id"],
                preferred_height=preferred_height,
                selected_height=video_track[
                    "height"
                ],
                audio=bool(
                    audio_track
                ),
                video_tracks=len(
                    video_tracks
                ),
                audio_tracks=len(
                    audio_tracks
                ),
            )

            return {
                "kind": "mp4-range",
                "quality": (
                    f"{video_track['height']}p"
                    if video_track[
                        "height"
                    ]
                    else "max"
                ),
                "video": video_track[
                    "url"
                ],
                "audio": (
                    audio_track[
                        "url"
                    ]
                    if audio_track
                    else None
                ),
            }

        except Exception as exc:
            log_event(
                "mpd_direct_tracks_error",
                id=item["id"],
                error=str(exc),
            )
            return None

        finally:
            client.close()

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
        raise RuntimeError("Brak mpv. Windows: rozpakuj całą paczkę z katalogiem mpv. macOS: brew install mpv. Linux: sudo apt install mpv.")

    def play(self, item, pdata, cancel_event=None):
        mpv = self.find_mpv()
        resolved = self.resolve(item, pdata)
        if not resolved:
            raise RuntimeError("Nie udało się pobrać strumienia filmu.")
        stream = resolved["video"]
        audio = resolved.get("audio")
        kind = resolved["kind"]
        quality = resolved.get("quality", "")
        position, duration = self.db.history_position(item["id"])
        if duration and position >= duration * 0.95:
            position = 0
        ipc_name = "cdafp-" + uuid.uuid4().hex
        ipc = "\\\\.\\pipe\\" + ipc_name if os.name == "nt" else str(Path(tempfile.gettempdir()) / (ipc_name + ".sock"))
        session = self.client.load_session()
        headers = ["Referer: " + item["url"]]
        if session:
            headers.append("User-Agent: " + session["user_agent"])
        log_path = LOG_DIR / f"mpv-{datetime.now():%Y%m%d-%H%M%S}-{ipc_name[-8:]}.log"
        input_file = tempfile.NamedTemporaryFile(
            mode="w", encoding="utf-8", prefix="cdafp-input-", suffix=".conf", delete=False
        )
        input_path = input_file.name
        input_file.write("ESC quit\nBS quit\nLEFT seek -10 relative+keyframes\nRIGHT seek 10 relative+keyframes\n")
        input_file.close()
        command = [
            mpv, "--no-config", "--fs", "--force-window=yes", "--ytdl=no", "--hwdec=auto-safe",
            "--cache=yes", "--cache-secs=15", "--demuxer-readahead-secs=12",
            "--input-conf=" + input_path,
            "--input-ipc-server=" + ipc, "--title=" + item["title"],
            "--http-header-fields=" + ",".join(header.replace("\\", "\\\\").replace(",", "\\,") for header in headers),
        ]
        if kind == "mp4-range":
            command.extend(["--demuxer-lavf-o=seekable=1", "--cache-secs=8"])
        if audio:
            command.append("--audio-file=" + audio)
        if position > 5:
            command.append("--start=" + str(position))
        if kind == "hls":
            command.append("--hls-bitrate=max")
        command.extend(["--", stream])
        cookie_path = None
        with self.lock:
            if cancel_event is not None and cancel_event.is_set():
                try: os.unlink(input_path)
                except FileNotFoundError: pass
                raise SearchCancelled("Przygotowanie filmu przerwane")
            if self.process is not None and self.process.poll() is None:
                try: os.unlink(input_path)
                except FileNotFoundError: pass
                raise RuntimeError("Film jest już odtwarzany. Zamknij jego okno przed otwarciem następnego.")
            if session and session.get("cookies"):
                with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", prefix="cdafp-cookies-", delete=False) as cookies:
                    cookie_path = cookies.name
                    cookies.write("# Netscape HTTP Cookie File\n")
                    for cookie in session["cookies"]:
                        domain = str(cookie.get("domain") or ".cda.pl")
                        fields = (domain, "TRUE" if domain.startswith(".") else "FALSE", str(cookie.get("path") or "/"),
                                  "TRUE" if cookie.get("secure") else "FALSE", "0", str(cookie.get("name", "")), str(cookie.get("value", "")))
                        if not any(char in field for field in fields for char in "\t\r\n"):
                            cookies.write("\t".join(fields) + "\n")
                command[1:1] = ["--cookies=yes", "--cookies-file=" + cookie_path]
            try:
                with log_path.open("w", encoding="utf-8") as log:
                    log.write(f"CDAFP source={resolved.get('source', '')} kind={kind} quality={quality} start={position:.3f}\n")
                    log.flush()
                    self.process = subprocess.Popen(
                        command, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                        creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
                    )
            except Exception:
                if cookie_path: os.unlink(cookie_path)
                try: os.unlink(input_path)
                except FileNotFoundError: pass
                raise
            process = self.process
            self.monitor = threading.Thread(
                target=self._monitor, args=(process, ipc, item, position, duration, cookie_path, input_path), daemon=True,
            )
            self.monitor.start()
        log_event("play", id=item["id"], stream_type=kind, quality=quality,
                  resolve_source=resolved.get("source", ""), separate_audio=bool(audio), resume=position)
        return kind, quality, log_path

    def _monitor(self, process, ipc, item, position, duration, cookie_path, input_path):
        conn = None
        ready = False
        last_save = 0
        buffer = b""
        try:
            deadline = time.monotonic() + 15
            while process.poll() is None and time.monotonic() < deadline:
                try:
                    if os.name == "nt":
                        conn = open(ipc, "r+b", buffering=0)
                    else:
                        conn = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                        conn.settimeout(0.25)
                        conn.connect(ipc)
                    break
                except OSError:
                    if conn is not None: conn.close()
                    conn = None
                    time.sleep(0.1)
            if conn is None:
                if process.poll() is None:
                    self.events.put(("error", "Odtwarzacz nie udostępnił zapisu postępu. Sprawdź log mpv."))
                    process.terminate()
                return
            commands = b"".join((json.dumps({"command": ["observe_property", i, name]}) + "\n").encode()
                                for i, name in enumerate(("time-pos", "duration"), 1))
            if os.name == "nt":
                import ctypes
                import msvcrt
                from ctypes import wintypes
                peek = ctypes.WinDLL("kernel32", use_last_error=True).PeekNamedPipe
                peek.argtypes = [wintypes.HANDLE, wintypes.LPVOID, wintypes.DWORD,
                                 ctypes.POINTER(wintypes.DWORD), ctypes.POINTER(wintypes.DWORD), ctypes.POINTER(wintypes.DWORD)]
                peek.restype = wintypes.BOOL
                handle = msvcrt.get_osfhandle(conn.fileno())
                conn.write(commands)
            else:
                conn.sendall(commands)
            while True:
                if os.name == "nt":
                    available = wintypes.DWORD()
                    if not peek(handle, None, 0, None, ctypes.byref(available), None):
                        break
                    if not available.value:
                        if process.poll() is not None: break
                        time.sleep(0.1)
                        continue
                    chunk = conn.read(min(65536, available.value))
                else:
                    try:
                        chunk = conn.recv(65536)
                    except socket.timeout:
                        if process.poll() is not None: break
                        continue
                if not chunk: break
                buffer += chunk
                while b"\n" in buffer:
                    line, buffer = buffer.split(b"\n", 1)
                    try:
                        event = json.loads(line)
                    except (ValueError, UnicodeError):
                        continue
                    if event.get("event") == "file-loaded":
                        ready = True
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
        except (OSError, ValueError) as exc:
            log_event("player_ipc_error", id=item["id"], error=type(exc).__name__)
        finally:
            if conn is not None: conn.close()
            if cookie_path:
                try: os.unlink(cookie_path)
                except FileNotFoundError: pass
            try: os.unlink(input_path)
            except FileNotFoundError: pass
            if ready and duration > 0:
                self.db.save_history(item, position, duration)
            if os.name != "nt":
                try: os.unlink(ipc)
                except FileNotFoundError: pass
            if process.poll() not in (None, 0):
                self.events.put(("error", "Odtwarzacz zakończył się błędem. Sprawdź log mpv w katalogu aplikacji."))
            self.events.put(("play_end", item["id"]))
            log_event("play_end", id=item["id"], position=position, duration=duration)

    def stop(self):
        with self.lock:
            process = self.process
        if process is not None and process.poll() is None:
            process.terminate()
            try: process.wait(timeout=3)
            except subprocess.TimeoutExpired: process.kill()
        if self.monitor is not None:
            self.monitor.join(timeout=2)
