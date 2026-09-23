import re
import tkinter as tk
from io import BytesIO

import httpx
from PIL import Image, ImageOps, ImageTk

from .config import (
    ACCENT,
    BG,
    BORDER,
    MUTED,
    PANEL,
    PANEL2,
    SELECTED,
    TEXT,
)
from .widgets import FlatButton
from .rating import (
    StarRatingView,
    normalize_rating,
)

GOLD = "#ffd22e"


class MovieInfoWindow:
    def __init__(
        self,
        root,
        item,
        metadata,
        favorite,
        icon_path,
        on_play,
        on_favorite,
        on_comments,
        avatar_pool,
        on_close=None,
    ):
        self.item = item
        self.metadata = metadata or {}
        self.on_comments = on_comments
        self.on_close = on_close
        self.action_index = 0
        self.body_mode = False
        self.avatar_pool = avatar_pool
        self.avatar_images = []
        self.avatar_generation = 0
        self.load_cancel_event = None
        self.loading = False

        self.win = tk.Toplevel(
            root
        )
        self.win.title(
            item.get(
                "title",
                "CDA Free Player",
            )
        )
        self.win.geometry(
            "1040x700"
        )
        self.win.minsize(
            720,
            520,
        )
        self.win.configure(
            bg=BG
        )
        self.win.transient(
            root
        )

        try:
            icon = tk.PhotoImage(
                file=str(icon_path)
            )
            self.win._icon = icon
            self.win.iconphoto(
                True,
                icon,
            )
        except Exception:
            pass

        self.win.protocol(
            "WM_DELETE_WINDOW",
            self.close,
        )
        self.win.bind(
            "<Escape>",
            self.back,
        )
        self.win.bind(
            "<BackSpace>",
            self.back,
        )
        self.win.bind(
            "<Left>",
            self.key_left,
        )
        self.win.bind(
            "<Right>",
            self.key_right,
        )
        self.win.bind(
            "<Up>",
            self.key_up,
        )
        self.win.bind(
            "<Down>",
            self.key_down,
        )
        self.win.bind(
            "<Return>",
            self.key_ok,
        )
        self.win.bind(
            "<KP_Enter>",
            self.key_ok,
        )
        self.win.bind("<MouseWheel>", self.mouse_wheel, add="+")
        self.win.bind("<Button-4>", self.mouse_wheel, add="+")
        self.win.bind("<Button-5>", self.mouse_wheel, add="+")

        top = tk.Frame(
            self.win,
            bg=PANEL,
            padx=18,
            pady=14,
        )
        top.pack(
            fill="x"
        )

        tk.Label(
            top,
            text=item.get(
                "title",
                "",
            ),
            bg=PANEL,
            fg=TEXT,
            font=(
                "Sans",
                17,
                "bold",
            ),
            anchor="w",
            wraplength=610,
            justify="left",
        ).pack(
            side="left",
            fill="x",
            expand=True,
        )

        score = self.metadata.get("rating")

        self.score_box = tk.Frame(
            top,
            bg=PANEL,
        )
        self.score_box.pack(
            side="right",
            padx=(14, 0),
        )

        self.stars = StarRatingView(
            self.score_box,
            score,
            star_size=19,
            gap=3,
            bg=PANEL,
        )

        self.score_label = tk.Label(
            self.score_box,
            text=self._score_text(
                score
            ),
            bg=PANEL,
            fg=TEXT,
            font=(
                "Sans",
                10,
                "bold",
            ),
        )

        if normalize_rating(
            score
        ) is not None:
            self.stars.pack(
                anchor="e"
            )
            self.score_label.pack(
                anchor="e",
                pady=(3, 0),
            )

        sources = []

        cda_votes = self.metadata.get(
            "cda_votes"
        )

        if cda_votes is not None:
            sources.append(
                f"CDA • {self._number(cda_votes)} ocen"
            )

        imdb = self.metadata.get(
            "imdb_rating"
        )

        if imdb:
            text = (
                f"IMDb {imdb} / 10"
            )
            votes = self.metadata.get(
                "imdb_votes"
            )

            if votes is not None:
                text += (
                    f" • {self._number(votes)} głosów"
                )

            sources.append(
                text
            )

        self.sources_label = tk.Label(
            self.win,
            text="   ".join(sources),
            bg=BG,
            fg=MUTED,
            font=("Sans", 9, "bold"),
            anchor="e",
        )
        self.sources_label.pack(fill="x", padx=18, pady=(6, 0))

        actions = tk.Frame(
            self.win,
            bg=BG,
            padx=18,
            pady=10,
        )
        actions.pack(
            fill="x"
        )

        self.favorite_btn = self._button(
            actions,
            (
                "♥ Usuń z ulubionych"
                if favorite
                else "♡ Dodaj do ulubionych"
            ),
            lambda: self._favorite(
                on_favorite
            ),
        )
        self.favorite_btn.pack(
            side="left",
            padx=(0, 6),
        )

        self.play_btn = self._button(
            actions,
            "▶ Odtwórz",
            on_play,
        )
        self.play_btn.pack(
            side="left",
            padx=6,
        )

        self.description_btn = self._button(
            actions,
            "Opis",
            self.show_description,
        )
        self.description_btn.pack(
            side="left",
            padx=6,
        )

        count = self.metadata.get(
            "comment_count"
        )
        self.comments_btn = self._button(
            actions,
            self._comments_label(
                count
            ),
            self.request_comments,
        )
        self.comments_btn.pack(
            side="left",
            padx=6,
        )

        self.action_buttons = [
            self.favorite_btn,
            self.play_btn,
            self.description_btn,
            self.comments_btn,
        ]

        self.title = tk.Label(
            self.win,
            text="Opis",
            bg=BG,
            fg=TEXT,
            font=(
                "Sans",
                13,
                "bold",
            ),
            anchor="w",
        )
        self.title.pack(
            fill="x",
            padx=18,
            pady=(4, 4),
        )

        body_wrap = tk.Frame(
            self.win,
            bg=BG,
        )
        body_wrap.pack(
            fill="both",
            expand=True,
            padx=18,
            pady=(0, 16),
        )

        self.body = tk.Text(
            body_wrap,
            bg=PANEL2,
            fg=TEXT,
            insertbackground=TEXT,
            wrap="word",
            relief="flat",
            bd=0,
            padx=14,
            pady=12,
            font=(
                "Sans",
                11,
            ),
            state="disabled",
        )
        self.body.pack(
            side="left",
            fill="both",
            expand=True,
        )

        scroll = tk.Scrollbar(
            body_wrap,
            orient="vertical",
            command=self.body.yview,
        )
        scroll.pack(
            side="right",
            fill="y",
        )
        self.body.configure(
            yscrollcommand=scroll.set,
        )

        self.loading_overlay = tk.Frame(
            self.win,
            bg=PANEL2,
            takefocus=True,
        )
        self.loading_text = tk.Label(
            self.loading_overlay,
            text="",
            bg=PANEL2,
            fg=TEXT,
            font=("Sans", 14, "bold"),
        )
        self.loading_text.place(
            relx=0.5,
            rely=0.5,
            anchor="center",
        )

        self.body.tag_configure(
            "author",
            foreground="#ff991e",
            font=(
                "Sans",
                11,
                "bold",
            ),
        )
        self.body.tag_configure(
            "ip",
            foreground=MUTED,
            font=(
                "Monospace",
                9,
            ),
        )
        self.body.tag_configure(
            "date",
            foreground=MUTED,
            font=(
                "Sans",
                9,
            ),
        )
        self.body.tag_configure(
            "score",
            foreground=GOLD,
            font=(
                "Sans",
                10,
                "bold",
            ),
        )
        self.body.tag_configure(
            "comment",
            foreground=TEXT,
            font=(
                "Sans",
                11,
            ),
            lmargin1=58,
            lmargin2=58,
            spacing1=2,
            spacing3=5,
        )
        self.body.tag_configure(
            "reply_header",
            lmargin1=54,
            lmargin2=54,
        )
        self.body.tag_configure(
            "reply_comment",
            foreground=TEXT,
            font=(
                "Sans",
                10,
            ),
            lmargin1=100,
            lmargin2=100,
            spacing1=2,
            spacing3=4,
        )
        self.body.tag_configure(
            "separator",
            foreground=BORDER,
        )

        self.show_description()
        self._focus_action(
            0
        )
        self.win.focus_force()

    @staticmethod
    def _number(value):
        try:
            return (
                f"{int(value):,}"
                .replace(
                    ",",
                    " ",
                )
            )
        except Exception:
            return str(value)

    @staticmethod
    def _comments_label(count):
        if count is None:
            return "Komentarze"

        return (
            f"Komentarze ({count})"
        )

    def _button(
        self,
        parent,
        text,
        command,
    ):
        return FlatButton(
            parent,
            text=text,
            command=command,
            bg=PANEL2,
            fg=TEXT,
            activebackground=ACCENT,
            activeforeground=TEXT,
            highlightthickness=3,
            highlightbackground=PANEL2,
            highlightcolor=ACCENT,
            relief="flat",
            bd=0,
            padx=12,
            pady=7,
            font=(
                "Sans",
                10,
                "bold",
            ),
            takefocus=True,
        )

    @staticmethod
    def _score_text(score):
        rating = normalize_rating(
            score
        )

        if rating is None:
            return ""

        return (
            f"{rating:.1f} / 5"
        )

    def _favorite(
        self,
        callback,
    ):
        state = bool(
            callback()
        )

        self.favorite_btn.configure(
            text=(
                "♥ Usuń z ulubionych"
                if state
                else "♡ Dodaj do ulubionych"
            )
        )

    def start_loading(self, text, cancel_event):
        if self.load_cancel_event is not None:
            self.load_cancel_event.set()
        self.load_cancel_event = cancel_event
        self.loading = True
        self.loading_text.configure(text=text)
        self.loading_overlay.place(
            x=0,
            y=0,
            relwidth=1,
            relheight=1,
        )
        self.loading_overlay.lift()
        self.loading_overlay.focus_set()

    def stop_loading(self):
        self.load_cancel_event = None
        self.loading = False
        self.loading_overlay.place_forget()
        self.body_mode = False
        self._focus_action(self.action_index)

    def cancel_loading(self):
        cancel_event = self.load_cancel_event
        self.load_cancel_event = None
        if cancel_event is not None:
            cancel_event.set()
        self.loading = False
        self.loading_overlay.place_forget()
        self.show_description()
        self.body_mode = False
        self._focus_action(self.action_index)

    def back(self, event=None):
        if self.loading:
            self.cancel_loading()
            return "break"
        self.close()
        return "break"

    def close(self):
        self.avatar_generation += 1
        if self.load_cancel_event is not None:
            self.load_cancel_event.set()
            self.load_cancel_event = None
        try:
            self.win.destroy()
        finally:
            if self.on_close:
                self.on_close()

    def _set_body(
        self,
        text,
    ):
        self.avatar_generation += 1
        self.avatar_images.clear()
        self.body.configure(
            state="normal"
        )
        self.body.delete(
            "1.0",
            "end",
        )
        self.body.insert(
            "1.0",
            text
            or "Brak danych.",
        )
        self.body.configure(
            state="disabled"
        )
        self.body.yview_moveto(
            0
        )

    def set_metadata(self, data):
        if not data:
            return
        self.metadata.update(data)
        score = self.metadata.get("rating")
        normalized = normalize_rating(score)
        if normalized is None:
            self.stars.pack_forget()
            self.score_label.pack_forget()
        else:
            self.stars.set_rating(normalized)
            self.score_label.configure(text=self._score_text(normalized))
            if not self.stars.winfo_ismapped():
                self.stars.pack(anchor="e")
            if not self.score_label.winfo_ismapped():
                self.score_label.pack(anchor="e", pady=(3, 0))
        sources = []
        cda_votes = self.metadata.get("cda_votes")
        if cda_votes is not None:
            sources.append(f"CDA • {self._number(cda_votes)} ocen")
        imdb = self.metadata.get("imdb_rating")
        if imdb:
            text = f"IMDb {imdb} / 10"
            votes = self.metadata.get("imdb_votes")
            if votes is not None:
                text += f" • {self._number(votes)} głosów"
            sources.append(text)
        self.sources_label.configure(text="   ".join(sources))
        count = self.metadata.get("comment_count")
        if count is not None:
            self.comments_btn.configure(text=self._comments_label(count))

    def show_description(self):
        self.body_mode = True
        self.title.configure(
            text="Pełny opis"
        )

        description = (
            self.metadata.get(
                "description"
            )
            or self.item.get(
                "short_description"
            )
            or "Brak opisu."
        )

        self._set_body(
            description
        )

    def set_description_loading(self, cancel_event):
        self.body_mode = True
        self.title.configure(text="Pełny opis")
        self.start_loading("Wczytywanie opisu…", cancel_event)

    def set_description(self, text):
        self.stop_loading()
        self.metadata["description"] = text or self.item.get("short_description", "")
        self.show_description()

    def set_description_error(self, error):
        self.stop_loading()
        self.body_mode = True
        self.title.configure(text="Pełny opis")
        fallback = self.item.get("short_description", "")
        self._set_body((error + "\n\n" + fallback).strip())

    def request_comments(self):
        self.body_mode = True
        count = self.metadata.get(
            "comment_count"
        )

        self.title.configure(
            text=self._comments_label(
                count
            )
        )
        self.on_comments(
            self
        )

    def set_comment_count(
        self,
        count,
    ):
        self.metadata[
            "comment_count"
        ] = count

        self.comments_btn.configure(
            text=self._comments_label(
                count
            )
        )

    def _load_avatar(self, url, image_name, size, generation):
        def worker():
            try:
                response = httpx.get(
                    url,
                    timeout=6,
                    follow_redirects=True,
                    headers={"User-Agent": "Mozilla/5.0"},
                )
                response.raise_for_status()
                if len(response.content) > 2 * 1024 * 1024:
                    return
                image = Image.open(BytesIO(response.content)).convert("RGB")
                image = ImageOps.fit(
                    image,
                    (size, size),
                    method=Image.Resampling.LANCZOS,
                )
                self.win.after(
                    0,
                    lambda: self._apply_avatar(
                        image_name,
                        image,
                        generation,
                    ),
                )
            except Exception:
                pass

        self.avatar_pool.submit(worker)

    def _apply_avatar(self, image_name, image, generation):
        if generation != self.avatar_generation or not self.win.winfo_exists():
            return
        try:
            photo = ImageTk.PhotoImage(
                image,
                master=self.body,
            )
            self.avatar_images.append(photo)
            self.body.image_configure(
                image_name,
                image=photo,
            )
        except Exception:
            pass

    def set_comments(
        self,
        comments,
    ):
        self.stop_loading()
        self.set_comment_count(
            len(comments)
        )
        self.title.configure(
            text=self._comments_label(
                len(comments)
            )
        )
        self.avatar_generation += 1
        generation = self.avatar_generation
        self.avatar_images.clear()
        self.body.configure(
            state="normal"
        )
        self.body.delete(
            "1.0",
            "end",
        )

        if not comments:
            self.body.insert(
                "end",
                "Brak komentarzy lub nie udało się ich pobrać.",
                "comment",
            )
        else:
            for index, comment in enumerate(comments):
                reply = bool(comment.get("reply"))
                if index:
                    if reply:
                        self.body.insert("end", "\n")
                    else:
                        self.body.insert(
                            "end",
                            "\n────────────────────────────────────────\n",
                            "separator",
                        )
                if reply:
                    self.body.insert("end", " ", "reply_header")

                size = 28 if reply else 36
                placeholder = ImageTk.PhotoImage(
                    Image.new("RGB", (size, size), PANEL),
                    master=self.body,
                )
                self.avatar_images.append(placeholder)
                image_name = self.body.image_create(
                    "end",
                    image=placeholder,
                    padx=3,
                    pady=2,
                )
                avatar = str(comment.get("avatar") or "").strip()
                if avatar:
                    self._load_avatar(
                        avatar,
                        image_name,
                        size,
                        generation,
                    )

                author = comment.get("author") or "anonim"
                ip = comment.get("ip") or ""
                date = comment.get("date") or ""
                rate = comment.get("rate") or ""
                self.body.insert("end", f"  {author}", "author")
                if ip:
                    self.body.insert("end", f"  {ip}", "ip")
                if date:
                    self.body.insert("end", f"   {date}", "date")
                if rate:
                    match = re.search(r"[-+]?\d+", rate)
                    score = match.group(0) if match else rate
                    if score and not score.startswith(("+", "-")):
                        try:
                            number = int(score)
                            score = f"+{number}" if number > 0 else str(number)
                        except Exception:
                            pass
                    self.body.insert("end", f"   ★ {score}", "score")
                self.body.insert("end", "\n")
                self.body.insert(
                    "end",
                    comment.get("text", ""),
                    "reply_comment" if reply else "comment",
                )

        self.body.configure(
            state="disabled"
        )
        self.body.yview_moveto(
            0
        )

    def set_comments_error(
        self,
        text,
    ):
        self.stop_loading()
        self.title.configure(
            text="Komentarze"
        )
        self._set_body(
            text
        )

    def _focus_action(
        self,
        index,
    ):
        if not self.action_buttons:
            return

        index = max(
            0,
            min(
                index,
                len(
                    self.action_buttons
                ) - 1,
            ),
        )

        for i, button in enumerate(
            self.action_buttons
        ):
            selected = (
                i == index
            )

            button.configure(
                bg=(
                    SELECTED
                    if selected
                    else PANEL2
                ),
                highlightbackground=(
                    ACCENT
                    if selected
                    else PANEL2
                ),
            )

        self.action_index = index

        try:
            self.action_buttons[
                index
            ].focus_set()
        except Exception:
            pass

    def mouse_wheel(self, event):
        if getattr(event, "num", None) == 4:
            units = -4
        elif getattr(event, "num", None) == 5:
            units = 4
        else:
            delta = getattr(event, "delta", 0)
            if not delta:
                return None
            units = -4 if delta > 0 else 4
        self.body_mode = True
        self.body.yview_scroll(units, "units")
        return "break"

    def key_left(
        self,
        event,
    ):
        self.body_mode = False
        self._focus_action(
            self.action_index - 1
        )
        return "break"

    def key_right(
        self,
        event,
    ):
        self.body_mode = False
        self._focus_action(
            self.action_index + 1
        )
        return "break"

    def key_up(
        self,
        event,
    ):
        if self.body_mode:
            self.body.yview_scroll(
                -4,
                "units",
            )
        else:
            self._focus_action(
                self.action_index
            )

        return "break"

    def key_down(
        self,
        event,
    ):
        self.body_mode = True
        self.body.yview_scroll(
            4,
            "units",
        )
        return "break"

    def key_ok(
        self,
        event,
    ):
        if not self.body_mode:
            self.action_buttons[
                self.action_index
            ].invoke()

        return "break"
