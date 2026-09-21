import json
import os
import re
import shutil
import socket
import subprocess
import threading
import time
import xml.etree.ElementTree as ET
from datetime import datetime
from urllib.parse import urljoin

from .client import log_event
from .config import LOG_DIR


class Player:
    def __init__(self, client, db, events):
        self.client = client
        self.db = db
        self.events = events

    def resolve(self, item, pdata):
        video = pdata.get(
            "video",
            {},
        )
        qualities = video.get(
            "qualities",
            {},
        )

        if isinstance(
            qualities,
            dict,
        ):
            candidates = []

            for label, value in qualities.items():
                match = re.search(
                    r"(\d+)",
                    label,
                )

                if match:
                    candidates.append((
                        int(
                            match.group(1)
                        ),
                        label,
                        value,
                    ))

            candidates.sort(
                reverse=True,
            )

            for height, label, value in candidates:
                stream = self.client.post_video_get_link(
                    item,
                    pdata,
                    value,
                )

                if not stream:
                    continue

                if stream.startswith("//"):
                    stream = (
                        "https:"
                        + stream
                    )

                clean = (
                    stream.lower()
                    .split("?", 1)[0]
                )

                if clean.endswith(".mp4"):
                    return {
                        "kind": "mp4",
                        "quality": label,
                        "video": stream,
                        "audio": None,
                        "source": "videoGetLink",
                    }

                if clean.endswith(".mpd"):
                    tracks = self._mpd_tracks(
                        item,
                        stream,
                        height,
                    )

                    if tracks:
                        tracks[
                            "quality"
                        ] = label
                        tracks[
                            "source"
                        ] = "mpd-direct-range"
                        return tracks

                    return {
                        "kind": "dash",
                        "quality": label,
                        "video": stream,
                        "audio": None,
                        "source": "videoGetLink-mpd-fallback",
                    }

                if clean.endswith(".m3u8"):
                    return {
                        "kind": "hls",
                        "quality": label,
                        "video": stream,
                        "audio": None,
                        "source": "videoGetLink",
                    }

        dash = video.get(
            "manifest"
        )

        if dash:
            if dash.startswith("//"):
                dash = (
                    "https:"
                    + dash
                )

            tracks = self._mpd_tracks(
                item,
                dash,
                None,
            )

            if tracks:
                tracks[
                    "quality"
                ] = "auto-max"
                tracks[
                    "source"
                ] = "player_data-mpd-direct-range"
                return tracks

            return {
                "kind": "dash",
                "quality": "auto",
                "video": dash,
                "audio": None,
                "source": "player_data-mpd-fallback",
            }

        hls = video.get(
            "manifest_apple"
        )

        if hls:
            if hls.startswith("//"):
                hls = (
                    "https:"
                    + hls
                )

            return {
                "kind": "hls",
                "quality": "auto",
                "video": hls,
                "audio": None,
                "source": "player_data-hls",
            }

        direct = video.get(
            "file"
        )

        if (
            direct
            and direct.startswith(
                "http"
            )
        ):
            return {
                "kind": "mp4",
                "quality": video.get(
                    "quality",
                    "",
                ),
                "video": direct,
                "audio": None,
                "source": "player_data-file",
            }

        return None

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

                    url = urljoin(
                        manifest_url,
                        base_text,
                    )

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

    def play(self, item, pdata):
        resolved = self.resolve(
            item,
            pdata,
        )

        if not resolved:
            raise RuntimeError(
                "Nie udało się pobrać strumienia filmu."
            )

        stream = resolved["video"]
        audio_stream = resolved.get(
            "audio"
        )
        kind = resolved["kind"]
        quality = resolved.get(
            "quality",
            "",
        )
        resolve_source = resolved.get(
            "source",
            "",
        )

        mpv = shutil.which("mpv")
        if not mpv:
            raise RuntimeError("Brak mpv.")

        start_pos, media_duration = self.db.history_position(item["id"])

        if media_duration and start_pos >= media_duration * 0.95:
            start_pos = 0

        ipc = f"/tmp/cda-free-player-{os.getpid()}-{item['id']}.sock"

        try:
            os.unlink(ipc)
        except FileNotFoundError:
            pass

        session = self.client.load_session()
        headers = [f"Referer: {item['url']}"]

        if session:
            headers.append(f"User-Agent: {session['user_agent']}")
            cookie = "; ".join(
                f"{c['name']}={c['value']}"
                for c in session["cookies"]
            )
            if cookie:
                headers.append(f"Cookie: {cookie}")

        log_path = LOG_DIR / (
            f"mpv-v6-{datetime.now().strftime('%Y%m%d-%H%M%S')}"
            f"-{item['id']}.log"
        )
        log_handle = open(log_path, "w", encoding="utf-8")

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
            command.extend([
                "--demuxer-lavf-o=seekable=1",
                "--cache-secs=8",
            ])

        if audio_stream:
            command.append(
                f"--audio-file={audio_stream}"
            )

        if start_pos > 5:
            command.append(
                f"--start={start_pos}"
            )

        if kind == "hls":
            command.append(
                "--hls-bitrate=max"
            )

        process = subprocess.Popen(
            command,
            stdout=log_handle,
            stderr=subprocess.STDOUT,
        )

        self.db.save_history(
            item,
            start_pos,
            media_duration,
        )

        log_event(
            "play",
            id=item["id"],
            stream_type=kind,
            quality=quality,
            resolve_source=resolve_source,
            separate_audio=bool(audio_stream),
            resume=start_pos,
            log=str(log_path),
        )

        threading.Thread(
            target=self._monitor,
            args=(
                process,
                ipc,
                item,
                start_pos,
                media_duration,
            ),
            daemon=True,
        ).start()

        return kind, quality, log_path

    def _monitor(
        self,
        process,
        ipc,
        item,
        position,
        duration,
    ):
        deadline = time.monotonic() + 15

        while (
            time.monotonic() < deadline
            and process.poll() is None
            and not os.path.exists(ipc)
        ):
            time.sleep(0.2)

        while process.poll() is None:
            try:
                position = self._property(ipc, "time-pos") or position
                duration = self._property(ipc, "duration") or duration
                self.db.save_history(
                    item,
                    position,
                    duration,
                )
            except Exception:
                pass

            time.sleep(5)

        self.db.save_history(
            item,
            position,
            duration,
        )

        try:
            os.unlink(ipc)
        except FileNotFoundError:
            pass

        log_event(
            "play_end",
            id=item["id"],
            position=position,
            duration=duration,
        )

    @staticmethod
    def _property(ipc, name):
        sock = socket.socket(
            socket.AF_UNIX,
            socket.SOCK_STREAM,
        )
        sock.settimeout(1.0)

        try:
            sock.connect(ipc)
            sock.sendall((
                json.dumps({
                    "command": ["get_property", name],
                }) + "\n"
            ).encode())

            data = b""

            while b"\n" not in data:
                chunk = sock.recv(4096)
                if not chunk:
                    break
                data += chunk

            if not data:
                return None

            response = json.loads(
                data.split(b"\n", 1)[0].decode()
            )

            if response.get("error") == "success":
                return response.get("data")
        finally:
            sock.close()

        return None
