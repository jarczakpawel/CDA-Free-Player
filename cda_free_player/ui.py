import queue
import re
import threading
import time
import tkinter as tk
from tkinter import messagebox
from hashlib import sha1
from io import BytesIO

import httpx
from PIL import Image, ImageTk

from .client import (
    CdaClient,
    SearchCancelled,
    log_event,
    parse_metadata,
    parse_comments,
    parse_player_data,
    parse_results,
)
from .config import (
    ACCENT,
    APP_NAME,
    BG,
    BORDER,
    CARD,
    CARD_FOCUS,
    CURRENT_YEAR,
    DEFAULT_YEAR,
    GRID_COLS,
    INITIAL_MAX_PAGES,
    INITIAL_TARGET,
    LOAD_AHEAD,
    META_DELAY_MS,
    SEARCH_PAGE_LIMIT,
    MUTED,
    PANEL,
    PANEL2,
    SELECTED,
    TEXT,
    THUMB_DIR,
    LOG_FILE,
    APP_ICON,
)
from .controller import DPadController
from .database import Database
from .player import Player
from .rating import StarRatingView, normalize_rating
from .movie_panel import MovieInfoWindow
from .settings_window import SettingsWindow


class App:
    def __init__(self, root):
        self.root = root
        self.root.title(APP_NAME)
        self.root.geometry("1380x840")
        self.root.minsize(1100, 680)
        self.root.configure(bg=BG)
        try:
            self.app_icon = tk.PhotoImage(file=str(APP_ICON))
            self.root.iconphoto(True, self.app_icon)
        except Exception:
            self.app_icon = None

        self.events = queue.Queue()
        self.client = CdaClient(self.events)
        self.db = Database()
        self.player = Player(
            self.client,
            self.db,
            self.events,
        )

        self.query = f"lektor {DEFAULT_YEAR}"
        self.query_label = f"Lektor {DEFAULT_YEAR}"
        self.selected_year = DEFAULT_YEAR
        self.sort_key = "best"
        self.duration_key = "all"
        self.view_mode = "search"

        self.search_token = 0
        self.search_cancel = threading.Event()
        self.meta_token = 0
        self.meta_after = None

        self.next_page = 1
        self.loading_page = False
        self.exhausted = False
        self.initial_pages = 0
        self.empty_pages_skipped = 0
        self.loading_spinner_job = None
        self.loading_spinner_index = 0
        self.loading_page_number = None
        self.desktop_scroll_touched = False
        self.mouse_pad_visible = False
        self.remove_mode = None

        self.items = []
        self.items_by_id = {}
        self.card_buttons = []
        self.card_frames = []
        self.card_rating_views = {}
        self.card_footer_frames = {}
        self.photos = {}
        self.comments_memory = {}
        self.movie_windows = {}

        self.catalog_meta_queue = queue.Queue()
        self.catalog_meta_pending = set()
        self.catalog_meta_attempted = set()
        self.catalog_meta_gate = threading.Event()
        self.catalog_meta_gate.set()
        threading.Thread(target=self.catalog_metadata_loop, daemon=True).start()

        self.year_buttons = {}
        self.sort_buttons = {}
        self.duration_buttons = {}
        self.left_buttons = []

        self.last_search = None
        self.detail_visible = False
        self.last_card_index = 0
        self.root._cda_last_card_index = 0

        placeholder_image = Image.new(
            "RGB",
            (210, 118),
            "#2a2f39",
        )
        self.placeholder = ImageTk.PhotoImage(
            placeholder_image,
        )

        self.build()

        self.controller = DPadController(
            self.root,
            self.on_controller_focus,
            self.on_controller_debug,
            self.on_controller_back,
            self.on_controller_ok,
        )
        self.register_controls()
        self.root.bind_all("<MouseWheel>", self.on_desktop_mousewheel, add="+")
        self.root.bind_all("<Button-4>", self.on_desktop_mousewheel, add="+")
        self.root.bind_all("<Button-5>", self.on_desktop_mousewheel, add="+")
        self.root.bind_all("<KeyPress-f>", self.favorite_shortcut, add="+")
        self.root.bind_all("<KeyPress-F>", self.favorite_shortcut, add="+")
        self.root.bind_all("<KeyPress-i>", lambda e: self.open_movie_panel("description"), add="+")
        self.root.bind_all("<KeyPress-I>", lambda e: self.open_movie_panel("description"), add="+")
        self.root.bind_all("<KeyPress-c>", lambda e: self.open_movie_panel("comments"), add="+")
        self.root.bind_all("<KeyPress-C>", lambda e: self.open_movie_panel("comments"), add="+")

        self.root._cda_year_index = (
            self.selected_year - 1950
        )
        self.root._cda_sort_index = 0
        self.root._cda_duration_index = 0

        self.root.after(60, self.pump)
        if not __import__("os").environ.get("CDA_FREE_PLAYER_TEST_NO_AUTOSTART"):
            self.root.after(
                250,
                lambda: self.start_search(
                self.query,
                self.query_label,
                    self.selected_year,
                ),
            )
            self.root.after(
                450,
                lambda: self.controller.focus(
                    "years",
                    self.selected_year - 1950,
                ),
            )
            self.root.after(
                520,
                lambda: self.center_year_in_bar(
                    self.selected_year
                ),
            )

    def tv_button(
        self,
        parent,
        text,
        command=None,
        width=None,
    ):
        return tk.Button(
            parent,
            text=text,
            command=command,
            bg=PANEL2,
            fg=TEXT,
            activebackground=SELECTED,
            activeforeground=TEXT,
            highlightthickness=3,
            highlightbackground=PANEL2,
            highlightcolor=ACCENT,
            relief="flat",
            bd=0,
            padx=10,
            pady=6,
            font=("Sans", 11, "bold"),
            takefocus=True,
            width=width,
        )

    def build(self):
        self.left = tk.Frame(
            self.root,
            bg=PANEL,
            width=310,
        )
        self.left.pack(
            side="left",
            fill="y",
        )
        self.left.pack_propagate(False)

        self.main = tk.Frame(
            self.root,
            bg=BG,
        )
        self.main.pack(
            side="left",
            fill="both",
            expand=True,
        )

        self.build_left_nav()
        self.build_left_detail()
        self.build_left_status()
        self.show_left_nav()

        top = tk.Frame(
            self.main,
            bg=BG,
        )
        top.pack(
            fill="x",
            padx=14,
            pady=(12, 6),
        )

        tk.Label(
            top,
            text="Rok",
            bg=BG,
            fg=MUTED,
            font=("Sans", 10, "bold"),
        ).pack(anchor="w")

        year_wrap = tk.Frame(
            top,
            bg=BG,
        )
        year_wrap.pack(
            fill="x",
            pady=(4, 8),
        )

        self.year_canvas = tk.Canvas(
            year_wrap,
            height=50,
            bg=BG,
            highlightthickness=0,
        )
        self.year_canvas.pack(fill="x")

        self.year_frame = tk.Frame(
            self.year_canvas,
            bg=BG,
        )

        # Fixed-focus TV carousel:
        # this spacer lets even the oldest year sit at the same
        # right-side focus position instead of being forced left.
        self.year_left_pad = tk.Frame(
            self.year_frame,
            bg=BG,
            width=0,
            height=1,
        )
        self.year_left_pad.pack(
            side="left",
        )
        self.year_left_pad.pack_propagate(
            False
        )

        self.year_window = self.year_canvas.create_window(
            (0, 0),
            window=self.year_frame,
            anchor="nw",
        )

        self.year_frame.bind(
            "<Configure>",
            lambda e: self._update_year_scrollregion(),
        )

        self.year_canvas.bind(
            "<Configure>",
            self.on_year_canvas_configure,
        )

        for year in range(
            1950,
            CURRENT_YEAR + 1,
        ):
            button = self.tv_button(
                self.year_frame,
                str(year),
                lambda y=year: self.choose_year(y),
                width=5,
            )
            button.pack(
                side="left",
                padx=2,
            )
            self.year_buttons[year] = button

        filters = tk.Frame(
            top,
            bg=BG,
        )
        filters.pack(fill="x")

        sort_box = tk.Frame(
            filters,
            bg=BG,
        )
        sort_box.pack(
            side="left",
            padx=(0, 18),
        )

        tk.Label(
            sort_box,
            text="Sortowanie",
            bg=BG,
            fg=MUTED,
            font=("Sans", 10, "bold"),
        ).pack(anchor="w")

        sort_row = tk.Frame(
            sort_box,
            bg=BG,
        )
        sort_row.pack(
            anchor="w",
            pady=(4, 0),
        )

        for key, label in (
            ("best", "Najtrafniejszy"),
            ("date", "Najnowsze"),
            ("alf", "Alfabetycznie"),
        ):
            button = self.tv_button(
                sort_row,
                label,
                lambda k=key: self.choose_sort(k),
            )
            button.pack(
                side="left",
                padx=(0, 4),
            )
            self.sort_buttons[key] = button

        duration_box = tk.Frame(
            filters,
            bg=BG,
        )
        duration_box.pack(side="left")

        tk.Label(
            duration_box,
            text="Długość",
            bg=BG,
            fg=MUTED,
            font=("Sans", 10, "bold"),
        ).pack(anchor="w")

        duration_row = tk.Frame(
            duration_box,
            bg=BG,
        )
        duration_row.pack(
            anchor="w",
            pady=(4, 0),
        )

        for key, label in (
            ("all", "Każda"),
            ("krotkie", "Krótkie <5 min"),
            ("srednie", "Średnie >20 min"),
            ("dlugie", "Długie >60 min"),
        ):
            button = self.tv_button(
                duration_row,
                label,
                lambda k=key: self.choose_duration(k),
            )
            button.pack(
                side="left",
                padx=(0, 4),
            )
            self.duration_buttons[key] = button

        header = tk.Frame(
            self.main,
            bg=BG,
        )
        header.pack(
            fill="x",
            padx=14,
            pady=(4, 5),
        )

        self.results_title = tk.Label(
            header,
            text="",
            bg=BG,
            fg=TEXT,
            font=("Sans", 17, "bold"),
        )
        self.results_title.pack(side="left")

        self.remove_collection_button = tk.Button(
            header,
            text="",
            command=self.toggle_remove_mode,
            bg="#3a1c22", fg="#ff8b97", activebackground="#6b202b", activeforeground=TEXT,
            relief="flat", bd=0, padx=9, pady=4, font=("Sans", 9, "bold"),
            takefocus=True,
        )

        self.count_label = tk.Label(
            header,
            text="",
            bg=BG,
            fg=MUTED,
            font=("Sans", 10),
        )
        self.count_label.pack(side="right")

        self.result_wrap = tk.Frame(
            self.main,
            bg=BG,
        )
        self.result_wrap.pack(
            fill="both",
            expand=True,
            padx=(14, 5),
            pady=(0, 5),
        )

        self.result_canvas = tk.Canvas(
            self.result_wrap,
            bg=BG,
            highlightthickness=0,
        )
        self.result_canvas.pack(
            side="left",
            fill="both",
            expand=True,
        )

        self.result_scrollbar = tk.Scrollbar(
            self.result_wrap,
            orient="vertical",
            command=self.on_result_scrollbar,
        )
        self.result_scrollbar.pack(
            side="right",
            fill="y",
        )

        self.result_canvas.configure(
            yscrollcommand=self.on_result_yscroll,
        )

        self.grid_frame = tk.Frame(
            self.result_canvas,
            bg=BG,
        )

        self.grid_window = self.result_canvas.create_window(
            (0, 0),
            window=self.grid_frame,
            anchor="nw",
        )

        # Non-blocking lazy-load indicator has its own lifecycle and is
        # deliberately NOT treated as a movie-card child during clear_results().
        self.grid_loading = None
        self.grid_loading_icon = None
        self.grid_loading_text = None
        self.create_grid_loading()

        self.grid_frame.bind(
            "<Configure>",
            lambda e: self.result_canvas.configure(
                scrollregion=self.result_canvas.bbox("all"),
            ),
        )

        self.result_canvas.bind(
            "<Configure>",
            lambda e: self.result_canvas.itemconfigure(
                self.grid_window,
                width=e.width,
            ),
        )

        self.status = tk.Label(
            self.main,
            text=f"Log: {LOG_FILE}",
            bg=PANEL2,
            fg=MUTED,
            anchor="w",
            padx=10,
            pady=4,
            font=("Sans", 9),
        )
        self.status.pack(
            fill="x",
            side="bottom",
        )

        self.paint_filters()

    def build_left_nav(self):
        self.nav_panel = tk.Frame(
            self.left,
            bg=PANEL,
        )

        brand = tk.Frame(self.nav_panel, bg=PANEL)
        brand.pack(fill="x", padx=14, pady=(14, 16))
        if self.app_icon is not None:
            self.brand_icon = self.app_icon.subsample(max(1, self.app_icon.width() // 52))
            tk.Label(brand, image=self.brand_icon, bg=PANEL).pack(side="left", padx=(0,10))
        tk.Label(brand, text=APP_NAME, bg=PANEL, fg=TEXT, font=("Sans", 23, "bold")).pack(side="left")

        recent = self.tv_button(
            self.nav_panel,
            "Ostatnio oglądane",
            self.show_recent,
        )
        recent.pack(
            fill="x",
            padx=12,
            pady=4,
        )

        favorite = self.tv_button(
            self.nav_panel,
            "Ulubione",
            self.show_favorites,
        )
        favorite.pack(
            fill="x",
            padx=12,
            pady=4,
        )

        tk.Frame(
            self.nav_panel,
            bg=BORDER,
            height=1,
        ).pack(
            fill="x",
            padx=12,
            pady=14,
        )

        tk.Label(
            self.nav_panel,
            text="Szukaj ręcznie",
            bg=PANEL,
            fg=MUTED,
            font=("Sans", 10, "bold"),
        ).pack(
            anchor="w",
            padx=14,
        )

        self.search_entry = tk.Entry(
            self.nav_panel,
            bg=PANEL2,
            fg=TEXT,
            insertbackground=TEXT,
            relief="flat",
            bd=0,
            highlightthickness=3,
            highlightbackground=PANEL2,
            highlightcolor=ACCENT,
            font=("Sans", 12),
        )
        self.search_entry.pack(
            fill="x",
            padx=12,
            pady=(6, 4),
            ipady=7,
        )
        self.search_entry.insert(
            0,
            "lektor 1988",
        )
        self.search_entry.bind(
            "<<DPadOK>>",
            lambda e: self.manual_search(),
        )

        search_button = self.tv_button(
            self.nav_panel,
            "Szukaj",
            self.manual_search,
        )
        search_button.pack(
            fill="x",
            padx=12,
            pady=4,
        )

        self.left_buttons = [
            recent,
            favorite,
            search_button,
        ]


    def create_grid_loading(self):
        if (
            self.grid_loading is not None
            and self.grid_loading.winfo_exists()
        ):
            return

        self.grid_loading = tk.Frame(
            self.grid_frame,
            bg=BG,
            height=42,
        )

        self.grid_loading_icon = tk.Label(
            self.grid_loading,
            text="",
            bg=BG,
            fg=ACCENT,
            font=("Sans", 15, "bold"),
        )
        self.grid_loading_icon.pack(
            side="left",
            padx=(12, 8),
            pady=8,
        )

        self.grid_loading_text = tk.Label(
            self.grid_loading,
            text="",
            bg=BG,
            fg=MUTED,
            font=("Sans", 10, "bold"),
            anchor="w",
        )
        self.grid_loading_text.pack(
            side="left",
            fill="x",
            expand=True,
            pady=8,
        )

    def grid_loading_exists(self):
        try:
            return (
                self.grid_loading is not None
                and bool(
                    self.grid_loading.winfo_exists()
                )
            )
        except tk.TclError:
            return False

    def build_left_status(self):
        self.left_status_frame = tk.Frame(
            self.left,
            bg="#121821",
            height=58,
        )
        self.left_status_frame.pack(
            side="bottom",
            fill="x",
        )
        self.left_status_frame.pack_propagate(
            False
        )

        self.left_status_icon = tk.Label(
            self.left_status_frame,
            text="●",
            bg="#121821",
            fg="#5f6875",
            font=("Sans", 10, "bold"),
        )
        self.left_status_icon.pack(
            side="left",
            padx=(12, 8),
        )

        status_text = tk.Frame(
            self.left_status_frame,
            bg="#121821",
        )
        status_text.pack(
            side="left",
            fill="both",
            expand=True,
            pady=7,
        )

        self.left_status_main = tk.Label(
            status_text,
            text="Gotowe",
            bg="#121821",
            fg=TEXT,
            font=("Sans", 9, "bold"),
            anchor="w",
        )
        self.left_status_main.pack(
            fill="x",
        )

        self.left_status_sub = tk.Label(
            status_text,
            text="",
            bg="#121821",
            fg=MUTED,
            font=("Sans", 8),
            anchor="w",
        )
        self.left_status_sub.pack(
            fill="x",
        )

        self.mouse_pad_toggle = tk.Button(
            self.left_status_frame,
            text="PAD",
            command=self.toggle_mouse_pad,
            bg="#1b2633", fg=TEXT, activebackground=SELECTED, activeforeground=TEXT,
            relief="flat", bd=0, padx=7, pady=4, font=("Sans", 8, "bold"),
            takefocus=False,
        )
        self.mouse_pad_toggle.pack(side="right", padx=(4, 8), pady=10)

        self.settings_toggle = tk.Button(
            self.left_status_frame,
            text="⚙",
            command=self.open_settings,
            bg="#1b2633", fg=TEXT, activebackground=SELECTED, activeforeground=TEXT,
            relief="flat", bd=0, padx=7, pady=4, font=("Sans", 10, "bold"),
            takefocus=False,
        )
        self.settings_toggle.pack(side="right", padx=(2, 0), pady=10)

        self.mouse_pad_popup = tk.Frame(
            self.left, bg="#16202b", highlightthickness=1, highlightbackground=BORDER,
            padx=5, pady=5,
        )
        def arrow(text, row, col, direction):
            b = tk.Button(
                self.mouse_pad_popup, text=text,
                command=lambda d=direction: self.desktop_pad_move(d),
                width=3, height=1, bg=PANEL2, fg=TEXT, activebackground=SELECTED,
                activeforeground=TEXT, relief="flat", bd=0, font=("Sans", 12, "bold"),
                takefocus=False,
            )
            b.grid(row=row, column=col, padx=2, pady=2)
        arrow("↑", 0, 1, "up")
        arrow("←", 1, 0, "left")
        arrow("↓", 1, 1, "down")
        arrow("→", 1, 2, "right")

    def build_left_detail(self):
        self.detail_panel = tk.Frame(
            self.left,
            bg=PANEL,
        )

        self.detail_thumb = tk.Label(
            self.detail_panel,
            bg=PANEL,
            image=self.placeholder,
        )
        self.detail_thumb.pack(
            anchor="w",
            padx=12,
            pady=(8, 2),
        )

        self.detail_title = tk.Label(
            self.detail_panel,
            text="",
            bg=PANEL,
            fg=TEXT,
            wraplength=284,
            justify="left",
            anchor="w",
            font=("Sans", 13, "bold"),
        )
        self.detail_title.pack(
            fill="x",
            padx=12,
            pady=(2, 3),
        )

        self.detail_rating_row = tk.Frame(
            self.detail_panel,
            bg=PANEL,
        )
        self.detail_rating_row.pack(
            fill="x",
            padx=12,
            pady=(1, 3),
        )
        self.detail_stars = StarRatingView(
            self.detail_rating_row,
            0,
            star_size=18,
            gap=3,
            bg=PANEL,
        )
        self.detail_stars.pack(side="left")
        self.detail_rating_text = tk.Label(
            self.detail_rating_row,
            text="— / 5",
            bg=PANEL,
            fg=TEXT,
            font=("Sans", 9, "bold"),
        )
        self.detail_rating_text.pack(side="left", padx=(8, 0))

        self.detail_source_ratings = tk.Label(
            self.detail_panel, text="", bg=PANEL, fg=MUTED,
            wraplength=284, justify="left", anchor="w", font=("Sans", 8, "bold"),
        )
        self.detail_source_ratings.pack(fill="x", padx=12, pady=(0, 2))

        self.detail_meta = tk.Label(
            self.detail_panel,
            text="",
            bg=PANEL,
            fg=MUTED,
            wraplength=284,
            justify="left",
            anchor="w",
            font=("Sans", 9),
        )
        self.detail_meta.pack(
            fill="x",
            padx=12,
            pady=(0, 3),
        )

        self.detail_actions = tk.Frame(
            self.detail_panel,
            bg=PANEL,
        )
        self.detail_actions.pack(
            fill="x",
            padx=12,
            pady=(2, 4),
        )

        self.detail_favorite_btn = tk.Button(
            self.detail_actions,
            text="♡ Ulubione",
            command=self.toggle_favorite_current,
            bg=PANEL2,
            fg=TEXT,
            activebackground=SELECTED,
            activeforeground=TEXT,
            highlightthickness=3,
            highlightbackground=PANEL2,
            highlightcolor=ACCENT,
            relief="flat",
            bd=0,
            padx=8,
            pady=4,
            font=(
                "Sans",
                9,
                "bold",
            ),
            anchor="w",
            takefocus=True,
        )
        self.detail_favorite_btn.pack(
            fill="x",
            pady=(0, 3),
        )

        self.detail_description_btn = tk.Button(
            self.detail_actions,
            text="Opis",
            command=lambda: self.open_movie_panel(
                "description"
            ),
            bg=PANEL2,
            fg=TEXT,
            activebackground=SELECTED,
            activeforeground=TEXT,
            highlightthickness=3,
            highlightbackground=PANEL2,
            highlightcolor=ACCENT,
            relief="flat",
            bd=0,
            padx=8,
            pady=4,
            font=(
                "Sans",
                9,
                "bold",
            ),
            anchor="w",
            takefocus=True,
        )
        self.detail_description_btn.pack(
            fill="x",
            pady=3,
        )

        self.detail_comments_btn = tk.Button(
            self.detail_actions,
            text="Komentarze",
            command=lambda: self.open_movie_panel(
                "comments"
            ),
            bg=PANEL2,
            fg=TEXT,
            activebackground=SELECTED,
            activeforeground=TEXT,
            highlightthickness=3,
            highlightbackground=PANEL2,
            highlightcolor=ACCENT,
            relief="flat",
            bd=0,
            padx=8,
            pady=4,
            font=(
                "Sans",
                9,
                "bold",
            ),
            anchor="w",
            takefocus=True,
        )
        self.detail_comments_btn.pack(
            fill="x",
            pady=(3, 0),
        )

        self.detail_action_buttons = [
            self.detail_favorite_btn,
            self.detail_description_btn,
            self.detail_comments_btn,
        ]

        self.detail_loading = tk.Label(
            self.detail_panel,
            text="",
            bg=PANEL,
            fg=ACCENT,
            anchor="w",
            font=("Sans", 8, "bold"),
        )
        self.detail_loading.pack(
            fill="x",
            padx=12,
            pady=(1, 0),
        )

        wrap = tk.Frame(
            self.detail_panel,
            bg=PANEL,
        )
        wrap.pack(
            fill="both",
            expand=True,
            padx=12,
            pady=(1, 8),
        )
        self.detail_text = tk.Text(
            wrap,
            bg=PANEL,
            fg=TEXT,
            relief="flat",
            bd=0,
            wrap="word",
            font=("Sans", 10),
            state="disabled",
            highlightthickness=0,
        )
        self.detail_text.pack(
            side="left",
            fill="both",
            expand=True,
        )
        scroll = tk.Scrollbar(
            wrap,
            orient="vertical",
            command=self.detail_text.yview,
        )
        scroll.pack(side="right", fill="y")
        self.detail_text.configure(yscrollcommand=scroll.set)


    def toggle_mouse_pad(self):
        if self.mouse_pad_visible:
            self.mouse_pad_popup.place_forget()
            self.mouse_pad_visible = False
            return
        self.left.update_idletasks()
        self.mouse_pad_popup.place(
            relx=0.5,
            rely=1.0,
            y=-64,
            anchor="s",
        )
        self.mouse_pad_popup.lift()
        self.mouse_pad_visible = True

    def desktop_pad_move(self, direction):
        if not hasattr(self, "controller"):
            return
        if self.controller.zone is None:
            self.controller.focus("years", self.selected_year - 1950)
        move = getattr(self.controller, direction, None)
        if move is not None:
            move()

    @staticmethod
    def _widget_inside(widget, parent):
        try:
            wp = str(widget)
            pp = str(parent)
            return wp == pp or wp.startswith(pp + ".")
        except Exception:
            return False

    def on_desktop_mousewheel(self, event):
        try:
            under = self.root.winfo_containing(event.x_root, event.y_root)
        except Exception:
            under = getattr(event, "widget", None)
        if under is None or not self._widget_inside(under, self.result_wrap):
            return None
        if getattr(event, "num", None) == 4:
            units = -2
        elif getattr(event, "num", None) == 5:
            units = 2
        else:
            delta = getattr(event, "delta", 0)
            if not delta:
                return "break"
            magnitude = max(1, min(4, int(abs(delta) / 120) if abs(delta) >= 120 else 1))
            units = -magnitude if delta > 0 else magnitude
        self.desktop_scroll_touched = True
        self.result_canvas.yview_scroll(units, "units")
        self.root.after_idle(self.maybe_load_more_from_scroll)
        return "break"

    def on_result_scrollbar(self, *args):
        self.desktop_scroll_touched = True
        self.result_canvas.yview(*args)
        self.root.after_idle(self.maybe_load_more_from_scroll)

    def on_result_yscroll(self, first, last):
        self.result_scrollbar.set(first, last)
        if self.desktop_scroll_touched:
            try:
                at_bottom = float(last) >= 0.985
            except Exception:
                at_bottom = False
            if at_bottom:
                self.root.after_idle(self.maybe_load_more_from_scroll)

    def maybe_load_more_from_scroll(self):
        if (
            self.view_mode == "search"
            and self.desktop_scroll_touched
            and not self.loading_page
            and not self.exhausted
            and self.items
        ):
            try:
                last = self.result_canvas.yview()[1]
            except Exception:
                return
            if last >= 0.985:
                self.load_next_page()

    def bind_card_mouse(self, root_widget, index):
        stack = [root_widget]
        while stack:
            widget = stack.pop()
            try:
                widget.bind(
                    "<Button-1>",
                    lambda event, i=index: self.controller.focus("cards", i),
                    add="+",
                )
                widget.bind(
                    "<Double-Button-1>",
                    lambda event, i=index: self.desktop_open_card(i),
                    add="+",
                )
                stack.extend(widget.winfo_children())
            except tk.TclError:
                pass

    def desktop_open_card(self, index):
        if not (0 <= index < len(self.items)):
            return "break"
        self.controller.focus("cards", index)
        if self.remove_mode:
            self.remove_selected_item(index)
        else:
            self.play_item(self.items[index])
        return "break"

    def register_controls(self):
        self.controller.register(
            "left",
            self.left_buttons,
            columns=1,
        )
        self.controller.register(
            "search_entry",
            [self.search_entry],
            columns=1,
        )
        self.controller.register(
            "years",
            list(self.year_buttons.values()),
            columns=len(self.year_buttons),
        )
        self.controller.register(
            "sort",
            list(self.sort_buttons.values()),
            columns=len(self.sort_buttons),
        )
        self.controller.register(
            "duration",
            list(self.duration_buttons.values()),
            columns=len(self.duration_buttons),
        )
        self.controller.register(
            "cards",
            self.card_buttons,
            columns=GRID_COLS,
        )
        self.controller.register(
            "detail_actions",
            self.detail_action_buttons,
            columns=1,
        )

        self.bind_mouse_zone(
            "detail_actions",
            self.detail_action_buttons,
        )
        self.bind_mouse_zone("left", self.left_buttons)
        self.bind_mouse_zone("search_entry", [self.search_entry])
        self.bind_mouse_zone("years", list(self.year_buttons.values()))
        self.bind_mouse_zone("sort", list(self.sort_buttons.values()))
        self.bind_mouse_zone("duration", list(self.duration_buttons.values()))
        self.bind_mouse_zone("cards", self.card_buttons)

    def bind_mouse_zone(self, zone, widgets):
        for index, widget in enumerate(widgets):
            widget.bind(
                "<Button-1>",
                lambda event, z=zone, i=index: self.controller.focus(z, i),
                add="+",
            )

    def refresh_card_zone(self):
        self.controller.register(
            "cards",
            self.card_buttons,
            columns=GRID_COLS,
        )
        self.bind_mouse_zone("cards", self.card_buttons)

    def on_controller_ok(
        self,
        zone,
        index,
        widget,
    ):
        if zone == "years":
            year = 1950 + index

            log_event(
                "YEAR_ACTIVATE",
                year=year,
                query=f"lektor {year}",
                sort=self.sort_key,
                duration=self.duration_key,
            )

            self.status.configure(
                text=(
                    f"Rok {year}: szukam "
                    f"'lektor {year}'..."
                ),
            )

            self.choose_year(year)
            return True

        if zone == "sort":
            keys = list(
                self.sort_buttons
            )
            self.choose_sort(
                keys[index]
            )
            return True

        if zone == "duration":
            keys = list(
                self.duration_buttons
            )
            self.choose_duration(
                keys[index]
            )
            return True

        if zone == "cards":
            if 0 <= index < len(self.items):
                if self.remove_mode:
                    self.remove_selected_item(index)
                else:
                    self.play_item(
                        self.items[index]
                    )
            return True

        if zone == "detail_actions":
            if widget is not None:
                widget.invoke()
            return True

        if zone == "left":
            if widget is not None:
                widget.invoke()
            return True

        if zone == "search_entry":
            self.manual_search()
            return True

        return False

    def on_controller_debug(
        self,
        zone,
        index,
        key,
    ):
        # Input diagnostics stay in logs; release UI does not show them.
        return


    def on_controller_focus(
        self,
        old_zone,
        old_index,
        zone,
        index,
        widget,
    ):
        self.unpaint_focus(
            old_zone,
            old_index,
        )
        self.paint_focus(
            zone,
            index,
        )

        if zone == "years":
            year = 1950 + index

            direction = 0

            if old_zone == "years":
                if index > old_index:
                    direction = 1
                elif index < old_index:
                    direction = -1

            # Normal TV horizontal list:
            # focus moves freely inside the visible viewport.
            # The strip scrolls only after focus reaches an edge.
            self.ensure_year_visible(
                year,
                direction,
            )
            self.show_left_nav()

        elif zone in (
            "sort",
            "duration",
            "left",
            "search_entry",
        ):
            self.show_left_nav()

        elif zone == "detail_actions":
            self.show_left_detail()

        elif zone == "cards":
            self.last_card_index = index
            self.root._cda_last_card_index = index
            self.ensure_card_visible(widget)
            self.show_movie_details(
                self.items[index],
            )

            last_row = (
                (len(self.items) - 1)
                // GRID_COLS
                if self.items
                else 0
            )
            focused_row = (
                index // GRID_COLS
            )

            if (
                focused_row >= last_row
                and not self.loading_page
                and not self.exhausted
            ):
                self.load_next_page()

    def cancel_active_search(
        self,
        reason="anulowane",
        invalidate=True,
    ):
        try:
            self.search_cancel.set()
        except Exception:
            pass

        if invalidate:
            self.search_token += 1

        self.loading_page = False
        self.hide_loading_indicator()

        log_event(
            "search_cancel",
            reason=reason,
            query=self.query,
            next_page=self.next_page,
        )

        self.set_left_status(
            "Wczytywanie przerwane",
            reason,
            loading=False,
        )

        self.status.configure(
            text=(
                f"Przerwano: {reason}"
            ),
        )

    def on_controller_back(self):
        if self.loading_page:
            self.cancel_active_search(
                "Backspace",
                invalidate=True,
            )

        if self.controller.zone == "cards":
            # First Back from a movie enters its left-side actions.
            self.show_left_detail()
            self.controller.focus(
                "detail_actions",
                0,
            )
            return

        if self.controller.zone == "detail_actions":
            # Second Back leaves the movie context and returns to main menu.
            self.show_left_nav()
            self.controller.focus(
                "left",
                0,
            )
            return

        if self.view_mode in (
            "recent",
            "favorites",
        ) and self.last_search:
            state = self.last_search
            self.sort_key = state["sort"]
            self.duration_key = state["duration"]
            self.selected_year = state["year"]
            self.paint_filters()
            self.start_search(
                state["query"],
                state["label"],
                state["year"],
            )
            self.controller.focus(
                "years",
                self.selected_year - 1950,
            )
            return

        self.show_left_nav()
        self.controller.focus(
            "left",
            0,
        )


    def paint_focus(
        self,
        zone,
        index,
    ):
        widget = self._zone_widget(
            zone,
            index,
        )
        if widget is None:
            return

        try:
            widget.configure(
                bg=CARD_FOCUS,
                highlightbackground=ACCENT,
            )
            if zone == "cards" and 0 <= index < len(self.items):
                item = self.items[index]
                frame = self.card_frames[index]
                frame.configure(bg=CARD_FOCUS)
                footer = self.card_footer_frames.get(item["id"])
                if footer is not None:
                    footer.configure(bg=CARD_FOCUS)
                    for child in footer.winfo_children():
                        try:
                            child.configure(bg=CARD_FOCUS)
                        except tk.TclError:
                            pass
                stars = self.card_rating_views.get(item["id"])
                if stars is not None:
                    stars.set_background(CARD_FOCUS)
        except tk.TclError:
            pass

    def unpaint_focus(
        self,
        zone,
        index,
    ):
        widget = self._zone_widget(
            zone,
            index,
        )
        if widget is None:
            return

        if zone == "years":
            year = 1950 + index
            self.paint_year(year)
            return

        if zone == "sort":
            key = list(self.sort_buttons)[index]
            self.paint_sort(key)
            return

        if zone == "duration":
            key = list(self.duration_buttons)[index]
            self.paint_duration(key)
            return

        try:
            widget.configure(
                bg=PANEL2 if zone != "cards" else CARD,
                highlightbackground=(PANEL2 if zone != "cards" else CARD),
            )
            if zone == "cards" and 0 <= index < len(self.items):
                item = self.items[index]
                self.card_frames[index].configure(bg=CARD)
                footer = self.card_footer_frames.get(item["id"])
                if footer is not None:
                    footer.configure(bg=CARD)
                    for child in footer.winfo_children():
                        try:
                            child.configure(bg=CARD)
                        except tk.TclError:
                            pass
                stars = self.card_rating_views.get(item["id"])
                if stars is not None:
                    stars.set_background(CARD)
        except tk.TclError:
            pass

    def _zone_widget(
        self,
        zone,
        index,
    ):
        data = self.controller.zones.get(zone)
        if not data:
            return None
        widgets = data["widgets"]
        if not 0 <= index < len(widgets):
            return None
        return widgets[index]

    def show_left_nav(self):
        self.detail_panel.pack_forget()
        self.nav_panel.pack(
            fill="both",
            expand=True,
        )
        self.detail_visible = False

    def show_left_detail(self):
        self.nav_panel.pack_forget()
        self.detail_panel.pack(
            fill="both",
            expand=True,
        )
        self.detail_visible = True

    def set_detail_text(self, text):
        self.detail_text.configure(state="normal")
        self.detail_text.delete(
            "1.0",
            "end",
        )
        self.detail_text.insert(
            "1.0",
            text or "",
        )
        self.detail_text.configure(state="disabled")

    def paint_year(self, year):
        button = self.year_buttons.get(year)
        if not button:
            return

        selected = year == self.selected_year

        button.configure(
            bg=SELECTED if selected else PANEL2,
            highlightbackground=(
                SELECTED if selected else PANEL2
            ),
        )

    def paint_sort(self, key):
        button = self.sort_buttons[key]
        selected = key == self.sort_key

        button.configure(
            bg=SELECTED if selected else PANEL2,
            highlightbackground=(
                SELECTED if selected else PANEL2
            ),
        )

    def paint_duration(self, key):
        button = self.duration_buttons[key]
        selected = key == self.duration_key

        button.configure(
            bg=SELECTED if selected else PANEL2,
            highlightbackground=(
                SELECTED if selected else PANEL2
            ),
        )

    def paint_filters(self):
        for year in self.year_buttons:
            self.paint_year(year)

        for key in self.sort_buttons:
            self.paint_sort(key)

        for key in self.duration_buttons:
            self.paint_duration(key)

    def on_year_canvas_configure(
        self,
        event,
    ):
        self.year_canvas.itemconfigure(
            self.year_window,
            height=event.height,
        )

        # No artificial left spacer in normal carousel mode.
        self.year_left_pad.configure(
            width=0
        )

        self.root.after_idle(
            self._update_year_scrollregion
        )

    def _update_year_scrollregion(
        self,
    ):
        self.root.update_idletasks()

        bbox = self.year_canvas.bbox(
            self.year_window
        )

        if bbox:
            self.year_canvas.configure(
                scrollregion=bbox,
            )

    def year_bar_bounds(self):
        self.root.update_idletasks()
        self._update_year_scrollregion()

        bbox = self.year_canvas.bbox(
            self.year_window
        )

        if not bbox:
            return (
                0.0,
                1.0,
                1.0,
            )

        left, _, right, _ = bbox

        total = max(
            1.0,
            float(
                right - left
            ),
        )

        visible = max(
            1.0,
            float(
                self.year_canvas.winfo_width()
            ),
        )

        return (
            float(left),
            total,
            visible,
        )

    def ensure_year_visible(
        self,
        year,
        direction=0,
    ):
        button = self.year_buttons.get(
            year
        )

        if not button:
            return

        self.root.update_idletasks()
        self._update_year_scrollregion()

        region_left, total, visible = (
            self.year_bar_bounds()
        )

        x = float(
            button.winfo_x()
        )

        width = max(
            1.0,
            float(
                button.winfo_width()
            ),
        )

        current_left = float(
            self.year_canvas.canvasx(0)
        )

        current_right = (
            current_left
            + visible
        )

        # Leave a tiny visual margin at each edge.
        edge_margin = 8.0

        target_left = None

        # Moving RIGHT:
        # do not scroll while the focused year is still fully visible.
        # Once it reaches/passes the right edge, move only enough
        # to reveal it at the right side.
        if direction > 0:
            if (
                x + width
                > current_right
                - edge_margin
            ):
                target_left = (
                    x
                    + width
                    - visible
                    + edge_margin
                )

        # Moving LEFT:
        # same behaviour mirrored.
        elif direction < 0:
            if (
                x
                < current_left
                + edge_margin
            ):
                target_left = (
                    x
                    - edge_margin
                )

        # Initial focus / resize:
        # only make sure the selected year is visible.
        else:
            if (
                x
                < current_left
                + edge_margin
            ):
                target_left = (
                    x
                    - edge_margin
                )

            elif (
                x + width
                > current_right
                - edge_margin
            ):
                target_left = (
                    x
                    + width
                    - visible
                    + edge_margin
                )

        if target_left is None:
            return

        max_left = max(
            0.0,
            total - visible,
        )

        target_left = max(
            0.0,
            min(
                target_left - region_left,
                max_left,
            ),
        )

        fraction = (
            target_left / total
            if total > 0
            else 0.0
        )

        self.year_canvas.xview_moveto(
            fraction
        )

        self.root.update_idletasks()


    def center_year_in_bar(
        self,
        year,
    ):
        button = self.year_buttons.get(
            year
        )

        if not button:
            return

        self.root.update_idletasks()
        self._update_year_scrollregion()

        bbox = self.year_canvas.bbox(
            self.year_window
        )

        if not bbox:
            return

        region_left, _, region_right, _ = bbox

        total = max(
            1.0,
            float(
                region_right
                - region_left
            ),
        )

        viewport = max(
            1.0,
            float(
                self.year_canvas.winfo_width()
            ),
        )

        x = float(
            button.winfo_x()
        )

        width = max(
            1.0,
            float(
                button.winfo_width()
            ),
        )

        target_left = (
            x
            + width / 2.0
            - viewport / 2.0
            - float(region_left)
        )

        max_left = max(
            0.0,
            total - viewport,
        )

        target_left = max(
            0.0,
            min(
                target_left,
                max_left,
            ),
        )

        self.year_canvas.xview_moveto(
            target_left / total
        )

    def ensure_card_visible(
        self,
        button,
    ):
        self.root.update_idletasks()

        bbox = self.result_canvas.bbox(
            self.grid_window
        )

        if not bbox:
            return

        region_left, region_top, region_right, region_bottom = bbox

        total_height = max(
            1.0,
            float(
                region_bottom
                - region_top
            ),
        )

        viewport_height = max(
            1.0,
            float(
                self.result_canvas.winfo_height()
            ),
        )

        item_top = float(
            button.master.winfo_y()
            if button.master is not self.grid_frame
            else button.winfo_y()
        )

        item_height = float(
            button.master.winfo_height()
            if button.master is not self.grid_frame
            else button.winfo_height()
        )

        if item_height <= 1:
            item_height = float(
                button.winfo_height()
            )

        item_bottom = (
            item_top
            + item_height
        )

        current_top = float(
            self.result_canvas.canvasy(0)
        )

        current_bottom = (
            current_top
            + viewport_height
        )

        # Small safe margin, like a TV lazy grid. Focus travels freely through
        # visible rows. Only when the focused row reaches an edge does the
        # viewport move enough to reveal that whole row.
        edge_margin = 10.0
        target_top = None

        if (
            item_bottom
            > current_bottom
            - edge_margin
        ):
            target_top = (
                item_bottom
                - viewport_height
                + edge_margin
            )

        elif (
            item_top
            < current_top
            + edge_margin
        ):
            target_top = (
                item_top
                - edge_margin
            )

        if target_top is None:
            return

        max_top = max(
            0.0,
            total_height
            - viewport_height,
        )

        target_top = max(
            0.0,
            min(
                target_top
                - float(region_top),
                max_top,
            ),
        )

        fraction = (
            target_top / total_height
            if total_height > 0
            else 0.0
        )

        self.result_canvas.yview_moveto(
            fraction
        )

        self.root.update_idletasks()


    def choose_year(self, year):
        self.selected_year = year
        self.root._cda_year_index = (
            year - 1950
        )

        self.paint_filters()

        query = f"lektor {year}"

        log_event(
            "year_search_start",
            year=year,
            query=query,
            sort=self.sort_key,
            duration=self.duration_key,
        )

        self.start_search(
            query,
            f"Lektor {year}",
            year,
        )

    def choose_sort(self, key):
        self.sort_key = key
        self.root._cda_sort_index = (
            list(self.sort_buttons).index(key)
        )
        self.paint_filters()

        if self.view_mode == "search":
            self.start_search(
                self.query,
                self.query_label,
                self.selected_year,
            )

    def choose_duration(self, key):
        self.duration_key = key
        self.root._cda_duration_index = (
            list(self.duration_buttons).index(key)
        )
        self.paint_filters()

        if self.view_mode == "search":
            self.start_search(
                self.query,
                self.query_label,
                self.selected_year,
            )

    def manual_search(self):
        query = self.search_entry.get().strip()

        if not query:
            return

        self.start_search(
            query,
            f'Wyniki: "{query}"',
            None,
        )

    def clear_results(self):
        self.desktop_scroll_touched = False
        # Stop any old lazy-load animation BEFORE touching the grid.
        self.hide_loading_indicator()

        self.items.clear()
        self.items_by_id.clear()
        self.card_buttons.clear()
        self.card_frames.clear()
        self.card_rating_views.clear()
        self.card_footer_frames.clear()
        self.photos.clear()

        for child in self.grid_frame.winfo_children():
            # The loader is infrastructure, not a search-result card.
            if (
                self.grid_loading_exists()
                and child == self.grid_loading
            ):
                continue

            try:
                child.destroy()
            except tk.TclError:
                pass

        # Defensive recreation in case Tk destroyed it for any external reason.
        self.create_grid_loading()

        self.result_canvas.yview_moveto(0)
        self.refresh_card_zone()
        self.show_left_nav()

    def save_last_search(self):
        if self.view_mode != "search":
            return

        self.last_search = {
            "query": self.query,
            "label": self.query_label,
            "year": self.selected_year,
            "sort": self.sort_key,
            "duration": self.duration_key,
        }

    def start_search(
        self,
        query,
        label,
        year,
    ):
        if self.view_mode == "search":
            self.save_last_search()

        self.view_mode = "search"
        self.set_remove_button(None)
        self.query = query
        self.query_label = label

        if year is not None:
            self.selected_year = year
            self.root._cda_year_index = (
                year - 1950
            )

        # New filter/year/query cancels the previous background search
        # and closes its browser fallback before using the same profile again.
        try:
            self.search_cancel.set()
        except Exception:
            pass

        self.search_token += 1
        self.search_cancel = threading.Event()

        self.next_page = 1
        self.loading_page = False
        self.exhausted = False
        self.initial_pages = 0
        self.empty_pages_skipped = 0

        # clear_results() owns cancellation/reset of loader callbacks.
        self.clear_results()

        self.results_title.configure(
            text=label,
        )
        self.count_label.configure(
            text="",
        )
        self.status.configure(
            text="Ładowanie...",
        )
        self.paint_filters()
        self.load_next_page()

    def set_left_status(
        self,
        main,
        sub="",
        loading=False,
    ):
        self.left_status_main.configure(
            text=main,
        )
        self.left_status_sub.configure(
            text=sub,
        )

        if loading:
            self.left_status_icon.configure(
                fg=ACCENT,
            )
        else:
            self.left_status_icon.configure(
                text="●",
                fg="#5f6875",
            )

    def show_loading_indicator(
        self,
        page,
    ):
        self.create_grid_loading()
        self.loading_page_number = page

        row = (
            (len(self.card_buttons)
             + GRID_COLS - 1)
            // GRID_COLS
        )

        self.grid_loading.grid(
            row=row,
            column=0,
            columnspan=GRID_COLS,
            sticky="ew",
            padx=5,
            pady=(2, 8),
        )

        self.grid_loading_text.configure(
            text=(
                f"Wczytywanie strony {page}…"
            ),
        )

        self.loading_spinner_index = 0

        if self.loading_spinner_job is None:
            self.animate_loading_spinner()

    def animate_loading_spinner(
        self,
    ):
        if (
            not self.loading_page
            or not self.grid_loading_exists()
        ):
            self.loading_spinner_job = None
            return

        frames = (
            "◐",
            "◓",
            "◑",
            "◒",
        )

        frame = frames[
            self.loading_spinner_index
            % len(frames)
        ]

        self.loading_spinner_index += 1

        try:
            self.grid_loading_icon.configure(
                text=frame,
            )
            self.left_status_icon.configure(
                text=frame,
                fg=ACCENT,
            )
        except tk.TclError:
            self.loading_spinner_job = None
            return

        self.loading_spinner_job = (
            self.root.after(
                120,
                self.animate_loading_spinner,
            )
        )

    def hide_loading_indicator(
        self,
    ):
        self.loading_page_number = None

        if self.loading_spinner_job is not None:
            try:
                self.root.after_cancel(
                    self.loading_spinner_job
                )
            except (tk.TclError, ValueError):
                pass

        self.loading_spinner_job = None
        self.loading_spinner_index = 0

        if self.grid_loading_exists():
            try:
                self.grid_loading.grid_remove()
            except tk.TclError:
                pass

            try:
                self.grid_loading_icon.configure(
                    text="",
                )
            except tk.TclError:
                pass

        try:
            self.left_status_icon.configure(
                text="●",
                fg="#5f6875",
            )
        except tk.TclError:
            pass


    def load_next_page(self):
        if (
            self.loading_page
            or self.exhausted
            or self.view_mode != "search"
        ):
            return

        if self.next_page > SEARCH_PAGE_LIMIT:
            self.exhausted = True
            self.hide_loading_indicator()
            self.set_left_status(
                "Koniec wyników",
                f"Limit {SEARCH_PAGE_LIMIT} stron",
                loading=False,
            )
            return

        token = self.search_token
        page = self.next_page
        query = self.query
        sort_key = self.sort_key
        duration_key = self.duration_key

        self.loading_page = True
        self.show_loading_indicator(
            page
        )

        self.status.configure(
            text=(
                f"{self.query_label} - "
                f"ładowanie p{page}..."
            ),
        )

        self.set_left_status(
            f"Wczytuję stronę {page}…",
            f"{len(self.items)} filmów już dostępnych",
            loading=True,
        )

        threading.Thread(
            target=self.page_worker,
            args=(
                token,
                page,
                query,
                sort_key,
                duration_key,
                self.search_cancel,
            ),
            daemon=True,
        ).start()

    def page_worker(
        self,
        token,
        page,
        query,
        sort_key,
        duration_key,
        cancel_event,
    ):
        cached = self.db.search_page(
            query,
            sort_key,
            duration_key,
            page,
        )

        if cached is not None:
            if cancel_event.is_set():
                return

            self.events.put((
                "page",
                token,
                page,
                cached,
                "cache",
                None,
            ))
            return

        url = self.client.search_url(
            query,
            sort_key,
            duration_key,
            page,
        )

        try:
            text, source = self.client.get_html(
                url,
                True,
                cancel_event=cancel_event,
            )
            videos, stats = parse_results(
                text
            )

            self.db.save_search_page(
                query,
                sort_key,
                duration_key,
                page,
                videos,
            )

            log_event(
                "page",
                query=query,
                sort=sort_key,
                duration=duration_key,
                page=page,
                url=url,
                source=source,
                **stats,
            )

            self.events.put((
                "page",
                token,
                page,
                videos,
                source,
                stats,
            ))
        except SearchCancelled:
            log_event(
                "page_cancelled",
                page=page,
                query=query,
            )
            self.events.put((
                "page_cancelled",
                token,
                page,
            ))

        except Exception as exc:
            log_event(
                "page_error",
                page=page,
                error=str(exc),
            )
            self.events.put((
                "page_error",
                token,
                page,
                str(exc),
            ))

    def queue_catalog_metadata(self, item):
        vid = item.get("id")
        if not vid or vid in self.catalog_meta_pending:
            return
        cached = self.db.metadata(vid)
        if cached and cached.get("rating") and cached.get("imdb_rating"):
            return
        self.catalog_meta_pending.add(vid)
        self.catalog_meta_queue.put(item.copy())

    def queue_missing_catalog_metadata(self):
        for item in list(self.items):
            if not item.get("short_rating"):
                self.queue_catalog_metadata(item)

    def catalog_metadata_loop(self):
        while True:
            item = self.catalog_meta_queue.get()
            self.catalog_meta_gate.wait()
            vid = item.get("id")
            try:
                cached = self.db.metadata(vid)
                if cached and cached.get("rating") and cached.get("imdb_rating"):
                    self.events.put(("catalog_metadata", vid, cached))
                    continue
                text, source = self.client.get_html(item["url"], False)
                if not text:
                    self.catalog_meta_gate.clear()
                    self.events.put(("catalog_metadata_security", vid))
                    continue
                data, _ = parse_metadata(text)
                self.db.save_metadata(vid, data)
                self.events.put(("catalog_metadata", vid, data))
                log_event("catalog_metadata", id=vid, source=source, rating=data.get("rating"), imdb=data.get("imdb_rating"))
            except Exception as exc:
                log_event("catalog_metadata_error", id=vid, error=str(exc))
            finally:
                self.catalog_meta_pending.discard(vid)
                self.catalog_meta_queue.task_done()
            time.sleep(0.45)

    @staticmethod
    def format_vote_count(value):
        if value is None:
            return ""
        try:
            return f"{int(value):,}".replace(",", " ")
        except Exception:
            return str(value)

    def source_rating_text(
        self,
        data,
    ):
        lines = []

        rating = data.get(
            "rating"
        )
        cda_votes = data.get(
            "cda_votes"
        )

        if rating:
            line = (
                f"CDA {rating} / 5"
            )

            if cda_votes is not None:
                line += (
                    " • "
                    + self.format_vote_count(
                        cda_votes
                    )
                    + " ocen"
                )

            lines.append(
                line
            )

        imdb_rating = data.get(
            "imdb_rating"
        )
        imdb_votes = data.get(
            "imdb_votes"
        )

        if imdb_rating:
            line = (
                f"IMDb {imdb_rating} / 10"
            )

            if imdb_votes is not None:
                line += (
                    " • "
                    + self.format_vote_count(
                        imdb_votes
                    )
                    + " głosów"
                )

            lines.append(
                line
            )

        return "\n".join(
            lines
        )


    def add_items(self, videos):
        for item in videos:
            if item["id"] in self.items_by_id:
                continue

            if not item.get("short_rating"):
                cached_meta = self.db.metadata(item["id"])
                if cached_meta and cached_meta.get("rating"):
                    item["short_rating"] = cached_meta["rating"]

            index = len(self.items)
            self.items.append(item)
            self.items_by_id[item["id"]] = item
            row = index // GRID_COLS
            col = index % GRID_COLS

            card = tk.Frame(
                self.grid_frame,
                bg=CARD,
                highlightthickness=3 if self.remove_mode else 0,
                highlightbackground="#ff3b4f" if self.remove_mode else CARD,
                bd=0,
            )
            card.grid(row=row, column=col, padx=5, pady=5, sticky="nsew")

            button_height = 148 if self.view_mode == "recent" else 158
            button = tk.Button(
                card,
                text=self.card_text(item),
                image=self.placeholder,
                compound="top",
                bg=CARD,
                fg=TEXT,
                activebackground=CARD_FOCUS,
                activeforeground=TEXT,
                highlightthickness=3,
                highlightbackground=CARD,
                highlightcolor=ACCENT,
                relief="flat",
                bd=0,
                font=("Sans", 10, "bold"),
                wraplength=210,
                justify="left",
                anchor="n",
                width=218,
                height=button_height,
                padx=4,
                pady=4,
                takefocus=True,
                command=lambda: None,
            )
            button.pack(fill="both", expand=True)

            footer = tk.Frame(card, bg=CARD, height=22)
            footer.pack(fill="x", padx=7, pady=(1,4))
            footer.pack_propagate(False)
            duration_label = tk.Label(
                footer, text=item.get("duration") or "", bg=CARD, fg=MUTED,
                font=("Sans", 8, "bold"), anchor="w"
            )
            duration_label.pack(side="left")
            rating_view = StarRatingView(
                footer,
                item.get("short_rating"),
                star_size=12,
                gap=2,
                bg=CARD,
            )
            rating_view.pack(side="right")

            self.add_progress_bar(card, item)

            self.bind_card_mouse(card, index)
            self.card_frames.append(card)
            self.card_buttons.append(button)
            self.card_rating_views[item["id"]] = rating_view
            self.card_footer_frames[item["id"]] = footer
            self.load_thumb(item, button, index)
            if not item.get("short_rating"):
                self.queue_catalog_metadata(item)

        for col in range(GRID_COLS):
            self.grid_frame.grid_columnconfigure(col, weight=1)
        self.refresh_card_zone()
        if self.loading_page:
            self.show_loading_indicator(self.loading_page_number or self.next_page)
        self.count_label.configure(text=f"{len(self.items)} filmów")

    def card_text(self, item):
        favorite = "  ♥" if self.db.is_favorite(item["id"]) else ""
        return f"{item['title']}{favorite}"

    def add_progress_bar(self, card, item):
        position = float(item.get("position") or 0)
        total = float(item.get("media_duration") or 0)
        if not position or not total:
            position, total = self.db.history_position(item["id"])
            position = float(position or 0)
            total = float(total or 0)
        if position <= 5 or total <= 0:
            return
        pct = max(0.0, min(1.0, position / total))
        bar = tk.Canvas(card, height=4, bg="#3c434e", highlightthickness=0, bd=0)
        bar.pack(fill="x", padx=7, pady=(0, 4))

        def redraw(event=None):
            width = max(1, bar.winfo_width())
            bar.delete("all")
            bar.create_rectangle(0, 0, width, 4, fill="#3c434e", outline="")
            bar.create_rectangle(0, 0, int(width * pct), 4, fill=ACCENT, outline="")

        bar.bind("<Configure>", redraw)
        bar.after_idle(redraw)


    def load_thumb(
        self,
        item,
        button,
        index,
    ):
        if not item.get("image"):
            return

        key = (
            sha1(item["image"].encode()).hexdigest()
            + ".jpg"
        )
        path = THUMB_DIR / key

        if path.exists():
            try:
                image = Image.open(path).convert("RGB")
                image.thumbnail((210, 118))
                photo = ImageTk.PhotoImage(image)
                self.photos[item["id"]] = photo
                button.configure(image=photo)
                return
            except Exception:
                pass

        threading.Thread(
            target=self.thumb_worker,
            args=(
                item["id"],
                item["image"],
                path,
                index,
            ),
            daemon=True,
        ).start()

    def thumb_worker(
        self,
        vid,
        url,
        path,
        index,
    ):
        try:
            response = httpx.get(
                url,
                timeout=10,
                follow_redirects=True,
            )
            response.raise_for_status()

            path.write_bytes(response.content)

            image = Image.open(
                BytesIO(response.content),
            ).convert("RGB")
            image.thumbnail((210, 118))

            self.events.put((
                "thumb",
                vid,
                index,
                image.copy(),
            ))
        except Exception as exc:
            log_event(
                "thumb_error",
                id=vid,
                error=str(exc),
            )

    @staticmethod
    def comments_button_text(
        count,
    ):
        if count is None:
            return "Komentarze"

        return (
            f"Komentarze ({count})"
        )

    def set_detail_rating(
        self,
        rating,
    ):
        normalized = normalize_rating(
            rating
        )

        if normalized is None:
            self.detail_rating_row.pack_forget()
            self.detail_stars.set_rating(
                None
            )
            self.detail_rating_text.configure(
                text=""
            )
            return

        if not self.detail_rating_row.winfo_ismapped():
            self.detail_rating_row.pack(
                fill="x",
                padx=12,
                pady=(1, 3),
                before=self.detail_source_ratings,
            )

        self.detail_stars.set_rating(
            normalized
        )
        self.detail_rating_text.configure(
            text=f"{normalized:.1f} / 5"
        )

    def update_card_rating(
        self,
        item,
        rating,
    ):
        normalized = normalize_rating(
            rating
        )

        stars = self.card_rating_views.get(
            item["id"]
        )

        if stars is None:
            return

        stars.set_rating(
            normalized
        )

    def show_movie_details(self, item):
        self.show_left_detail()
        self.detail_title.configure(text=item["title"])

        rating = item.get(
            "short_rating"
        )
        self.set_detail_rating(
            rating
        )

        meta = []
        if item.get("duration"):
            meta.append(f"Czas: {item['duration']}")
        position, total = self.db.history_position(item["id"])
        if position > 5:
            progress = f"Postęp: {self.format_time(position)}"
            if total:
                progress += f" / {self.format_time(total)}"
            meta.append(progress)
        self.detail_meta.configure(text="\n".join(meta))
        self.detail_source_ratings.configure(
            text=""
        )

        cached_comments = self.db.comments(
            item["id"]
        )
        initial_count = (
            len(cached_comments)
            if cached_comments is not None
            else None
        )
        self.detail_comments_btn.configure(
            text=self.comments_button_text(
                initial_count
            )
        )

        self.detail_favorite_btn.configure(
            text="♥ Usuń" if self.db.is_favorite(item["id"]) else "♡ Ulubione"
        )

        short = item.get("short_description", "").strip() or "Brak skróconego opisu na liście."
        self.set_detail_text(short)
        photo = self.photos.get(item["id"])
        self.detail_thumb.configure(image=photo or self.placeholder)

        cached = self.db.metadata(item["id"])
        if cached:
            self.apply_metadata(item, cached)
            self.detail_loading.configure(text="")
            return

        self.detail_loading.configure(text="⟳ Wczytywanie pełnego opisu…")
        self.meta_token += 1
        token = self.meta_token
        if self.meta_after:
            try:
                self.root.after_cancel(self.meta_after)
            except Exception:
                pass
        self.meta_after = self.root.after(
            META_DELAY_MS,
            lambda: threading.Thread(target=self.metadata_worker, args=(token, item.copy()), daemon=True).start(),
        )


    def metadata_worker(
        self,
        token,
        item,
    ):
        try:
            text, source = self.client.get_html(
                item["url"],
                False,
            )

            if not text:
                self.events.put((
                    "metadata_challenge",
                    token,
                    item["id"],
                ))
                return

            data, _ = parse_metadata(text)
            self.db.save_metadata(
                item["id"],
                data,
            )

            self.events.put((
                "metadata",
                token,
                item["id"],
                data,
            ))

            log_event(
                "metadata",
                id=item["id"],
                source=source,
                description_len=len(
                    data["description"],
                ),
            )
        except Exception as exc:
            self.events.put((
                "metadata_error",
                token,
                item["id"],
                str(exc),
            ))

    def apply_metadata(self, item, data):
        current = self.current_card_item()
        if not current or current["id"] != item["id"]:
            return

        rating = (
            data.get("rating")
            or item.get(
                "short_rating"
            )
        )

        if normalize_rating(
            rating
        ) is not None:
            item["short_rating"] = str(
                rating
            )
            self.update_card_rating(
                item,
                rating,
            )

        self.set_detail_rating(
            rating
        )

        self.detail_source_ratings.configure(
            text=self.source_rating_text(
                data
            )
        )

        comment_count = data.get(
            "comment_count"
        )

        if comment_count is None:
            cached_comments = self.db.comments(
                item["id"]
            )

            if cached_comments is not None:
                comment_count = len(
                    cached_comments
                )

        self.detail_comments_btn.configure(
            text=self.comments_button_text(
                comment_count
            )
        )

        meta = []
        if item.get("duration"):
            meta.append(f"Czas: {item['duration']}")
        position, total = self.db.history_position(item["id"])
        if position > 5:
            progress = f"Postęp: {self.format_time(position)}"
            if total:
                progress += f" / {self.format_time(total)}"
            meta.append(progress)
        self.detail_meta.configure(text="\n".join(meta))
        self.detail_favorite_btn.configure(
            text="♥ Usuń" if self.db.is_favorite(item["id"]) else "♡ Ulubione"
        )
        self.set_detail_text(
            data.get("description", "").strip()
            or item.get("short_description", "")
            or "Brak opisu."
        )
        self.detail_loading.configure(text="")


    def current_card_item(self):
        if self.controller.zone == "cards":
            index = self.controller.index

        elif self.controller.zone == "detail_actions":
            index = self.last_card_index

        else:
            return None

        if not (
            0 <= index
            < len(self.items)
        ):
            return None

        return self.items[index]


    def favorite_shortcut(self, event=None):
        if self.root.focus_get() is self.search_entry:
            return None
        self.toggle_favorite_current()
        return "break"

    def open_movie_panel(self, mode="description"):
        item = self.current_card_item()
        if not item:
            return
        metadata = self.db.metadata(item["id"]) or {
            "description": item.get("short_description", ""),
            "rating": item.get("short_rating", ""),
        }
        old = self.movie_windows.get(item["id"])
        if old is not None:
            try:
                old.win.destroy()
            except Exception:
                pass
        panel = MovieInfoWindow(
            self.root,
            item,
            metadata,
            self.db.is_favorite(item["id"]),
            APP_ICON,
            on_play=lambda: self.play_item(item),
            on_favorite=lambda: self.toggle_favorite_item(item),
            on_comments=lambda view: self.load_comments_for_panel(
                item,
                view,
            ),
            on_close=lambda: self.restore_card_focus(
                self.last_card_index
            ),
        )
        self.movie_windows[item["id"]] = panel
        if mode == "comments":
            panel.request_comments()

    def restore_card_focus(
        self,
        index,
    ):
        if not self.card_buttons:
            return

        index = max(
            0,
            min(
                index,
                len(
                    self.card_buttons
                ) - 1,
            ),
        )

        self.root.after(
            20,
            lambda: self.controller.focus(
                "cards",
                index,
            ),
        )

    def toggle_favorite_item(self, item):
        state = self.db.toggle_favorite(item)
        if item["id"] in self.items_by_id:
            try:
                idx = self.items.index(self.items_by_id[item["id"]])
                self.card_buttons[idx].configure(text=self.card_text(self.items[idx]))
            except Exception:
                pass
        current = self.current_card_item()
        if current and current["id"] == item["id"]:
            self.detail_favorite_btn.configure(text="♥ Usuń" if state else "♡ Ulubione")
        self.status.configure(text="Dodano do ulubionych." if state else "Usunięto z ulubionych.")
        return state

    def load_comments_for_panel(self, item, panel):
        cached = self.db.comments(item["id"])
        if cached is not None:
            panel.set_comments(
                cached
            )

            current = self.current_card_item()

            if (
                current
                and current["id"]
                == item["id"]
            ):
                self.detail_comments_btn.configure(
                    text=self.comments_button_text(
                        len(cached)
                    )
                )

            return
        threading.Thread(
            target=self.comments_worker,
            args=(item.copy(), panel),
            daemon=True,
        ).start()

    def comments_worker(self, item, panel):
        try:
            text, source = self.client.get_html(item["url"], False)
            if not text:
                self.events.put(("comments_error", item["id"], panel, "Komentarze wymagają aktywnej sesji CDA."))
                return
            comments = parse_comments(text)
            self.db.save_comments(
                item["id"],
                comments,
            )
            self.events.put((
                "comments",
                item["id"],
                panel,
                comments,
            ))
            log_event("comments", id=item["id"], source=source, count=len(comments))
        except Exception as exc:
            self.events.put(("comments_error", item["id"], panel, f"Nie udało się pobrać komentarzy: {exc}"))

    def open_settings(self):
        SettingsWindow(
            self.root,
            self.db,
            on_data_changed=self.refresh_after_settings,
        )

    def refresh_after_settings(self):
        if self.view_mode == "recent":
            self.show_recent()
        elif self.view_mode == "favorites":
            self.show_favorites()
        else:
            for item in self.items:
                item["favorite"] = self.db.is_favorite(item["id"])
            self.clear_results()
            # Re-run the current search to avoid rebuilding cards from stale state.
            self.start_search(self.query, self.query_label, self.selected_year)

    def set_remove_button(self, mode):
        self.remove_mode = None
        if not hasattr(self, "remove_collection_button"):
            return
        self.remove_collection_button.pack_forget()
        if mode == "recent":
            self.remove_collection_button.configure(text="Usuń z oglądanych")
            self.remove_collection_button.pack(side="right", padx=(8, 8))
        elif mode == "favorites":
            self.remove_collection_button.configure(text="Usuń z ulubionych")
            self.remove_collection_button.pack(side="right", padx=(8, 8))
        self.paint_remove_mode()

    def toggle_remove_mode(self):
        if self.view_mode not in ("recent", "favorites"):
            return
        self.remove_mode = None if self.remove_mode else self.view_mode
        self.remove_collection_button.configure(
            text=(
                "Anuluj usuwanie"
                if self.remove_mode
                else ("Usuń z oglądanych" if self.view_mode == "recent" else "Usuń z ulubionych")
            )
        )
        self.paint_remove_mode()
        if self.remove_mode and self.card_buttons:
            self.controller.focus("cards", min(self.controller.index if self.controller.zone == "cards" else 0, len(self.card_buttons)-1))

    def paint_remove_mode(self):
        active = bool(self.remove_mode)
        for frame, button in zip(self.card_frames, self.card_buttons):
            try:
                frame.configure(
                    highlightthickness=3 if active else 0,
                    highlightbackground="#ff3b4f" if active else CARD,
                )
                button.configure(
                    highlightcolor="#ff3b4f" if active else ACCENT,
                    highlightbackground="#70212b" if active else CARD,
                )
            except tk.TclError:
                pass

    def remove_selected_item(self, index):
        if not self.remove_mode or not (0 <= index < len(self.items)):
            return
        item = self.items[index]
        label = "oglądanych" if self.remove_mode == "recent" else "ulubionych"
        yes = messagebox.askyesno(
            "Potwierdzenie",
            f"Usunąć „{item['title']}” z {label}?",
            parent=self.root,
        )
        mode = self.remove_mode
        self.remove_mode = None
        if yes:
            if mode == "recent":
                self.db.remove_history(item["id"])
            else:
                self.db.remove_favorite(item["id"])
            if mode == "recent":
                self.show_recent()
            else:
                self.show_favorites()
        else:
            self.set_remove_button(mode)

    def show_recent(self):
        self.save_last_search()
        self.cancel_active_search(
            "Ostatnio oglądane",
            invalidate=True,
        )

        self.view_mode = "recent"
        self.set_remove_button("recent")
        self.clear_results()

        self.results_title.configure(
            text="Ostatnio oglądane",
        )

        rows = self.db.history()
        self.add_items(rows)

        self.status.configure(
            text=f"Ostatnio oglądane: {len(rows)}",
        )
        self.set_left_status(
            f"Ostatnio oglądane • {len(rows)}",
            "Postęp zapisany automatycznie",
            loading=False,
        )

        if self.card_buttons:
            self.controller.focus(
                "cards",
                0,
            )

    def show_favorites(self):
        self.save_last_search()
        self.cancel_active_search(
            "Ulubione",
            invalidate=True,
        )

        self.view_mode = "favorites"
        self.set_remove_button("favorites")
        self.clear_results()

        self.results_title.configure(
            text="Ulubione",
        )

        rows = self.db.favorites()
        self.add_items(rows)

        self.status.configure(
            text=f"Ulubione: {len(rows)}",
        )
        self.set_left_status(
            f"Ulubione • {len(rows)}",
            "",
            loading=False,
        )

        if self.card_buttons:
            self.controller.focus(
                "cards",
                0,
            )

    def play_item(self, item):
        self.status.configure(
            text=f"Przygotowanie: {item['title']}",
        )

        threading.Thread(
            target=self.play_worker,
            args=(item.copy(),),
            daemon=True,
        ).start()

    def play_worker(self, item):
        try:
            page_html, source = self.client.get_html(
                item["url"],
                True,
            )

            metadata, pdata = parse_metadata(
                page_html,
            )

            if not pdata:
                raise RuntimeError(
                    "Brak player_data.",
                )

            if pdata.get("premium"):
                raise RuntimeError(
                    "Materiał Premium - pominięty.",
                )

            self.db.save_metadata(
                item["id"],
                metadata,
            )

            kind, quality, log_path = self.player.play(
                item,
                pdata,
            )

            self.events.put((
                "status",
                (
                    f"Odtwarzanie {quality} ({kind})"
                    + (
                        " - bezpośredni MP4/Range"
                        if kind == "mp4-range"
                        else ""
                    )
                ),
            ))

            self.events.put((
                "metadata_direct",
                item["id"],
                metadata,
            ))

            log_event(
                "play_page_source",
                id=item["id"],
                source=source,
                log=str(log_path),
            )
        except Exception as exc:
            log_event(
                "play_error",
                id=item["id"],
                error=str(exc),
            )
            self.events.put((
                "error",
                str(exc),
            ))

    def toggle_favorite_current(self):
        item = self.current_card_item()
        if not item:
            return False
        return self.toggle_favorite_item(item)


    @staticmethod
    def format_time(seconds):
        seconds = int(seconds or 0)
        hours, remainder = divmod(
            seconds,
            3600,
        )
        minutes, seconds = divmod(
            remainder,
            60,
        )

        if hours:
            return (
                f"{hours}:{minutes:02d}:{seconds:02d}"
            )

        return (
            f"{minutes}:{seconds:02d}"
        )

    def pump(self):
        try:
            while True:
                event = self.events.get_nowait()
                kind = event[0]

                if kind == "page":
                    (
                        _,
                        token,
                        page,
                        videos,
                        source,
                        stats,
                    ) = event

                    if (
                        token != self.search_token
                        or self.view_mode != "search"
                    ):
                        continue

                    self.loading_page = False
                    self.hide_loading_indicator()
                    self.initial_pages += 1
                    self.next_page = page + 1

                    raw_count = (
                        stats.get("raw", 0)
                        if stats
                        else len(videos)
                    )

                    self.add_items(videos)

                    # A page with HTML results but zero FREE videos is not the
                    # end. It may simply contain only Premium/folders.
                    # Skip it automatically and continue to pN+1.
                    page_has_results = (
                        raw_count > 0
                    )

                    if not page_has_results:
                        self.exhausted = True

                    if videos:
                        self.empty_pages_skipped = 0
                    elif page_has_results:
                        self.empty_pages_skipped += 1

                    suffix = ""

                    if stats:
                        reasons = stats.get(
                            "premium_reasons",
                            {},
                        )

                        suffix = (
                            f" | raw={stats['raw']}"
                            f" PREMIUM-ODRZUCONE={stats['premium']}"
                            f" inne={stats['nonvideo']}"
                        )

                        if reasons:
                            suffix += (
                                " "
                                + ",".join(
                                    f"{key}:{value}"
                                    for key, value
                                    in reasons.items()
                                )
                            )

                    self.status.configure(
                        text=(
                            f"{self.query!r} | "
                            f"p{page} {source}: "
                            f"+{len(videos)} "
                            f"razem={len(self.items)}"
                            f"{suffix}"
                        ),
                    )

                    if self.exhausted:
                        self.set_left_status(
                            "Koniec wyników",
                            f"{len(self.items)} darmowych filmów",
                            loading=False,
                        )

                    elif not videos:
                        self.set_left_status(
                            f"Strona {page}: brak darmowych",
                            "Pomijam i sprawdzam następną…",
                            loading=False,
                        )
                        self.root.after(
                            90,
                            self.load_next_page,
                        )

                    else:
                        premium_count = (
                            stats.get("premium", 0)
                            if stats
                            else 0
                        )

                        self.set_left_status(
                            f"Gotowe • {len(self.items)} filmów",
                            (
                                f"p{page}: +{len(videos)}"
                                + (
                                    f" • Premium pominięte: {premium_count}"
                                    if premium_count
                                    else ""
                                )
                            ),
                            loading=False,
                        )

                        # Initial fill: only fetch enough pages to populate a
                        # useful first screen. Later pages are edge-triggered.
                        if (
                            len(self.items) < INITIAL_TARGET
                            and self.initial_pages < INITIAL_MAX_PAGES
                        ):
                            self.root.after(
                                180,
                                self.load_next_page,
                            )

                elif kind == "page_cancelled":
                    _, token, page = event

                    # A cancelled/stale search is intentionally silent.
                    if token == self.search_token:
                        self.loading_page = False
                        self.hide_loading_indicator()

                elif kind == "page_error":
                    _, token, page, error = event

                    if token == self.search_token:
                        self.loading_page = False
                        self.hide_loading_indicator()
                        self.status.configure(
                            text=f"Błąd p{page}: {error}",
                        )
                        self.set_left_status(
                            f"Błąd strony {page}",
                            str(error)[:70],
                            loading=False,
                        )

                elif kind == "thumb":
                    _, vid, index, image = event

                    if (
                        index < len(self.card_buttons)
                        and index < len(self.items)
                        and self.items[index]["id"] == vid
                    ):
                        photo = ImageTk.PhotoImage(
                            image,
                        )
                        self.photos[vid] = photo
                        self.card_buttons[index].configure(
                            image=photo,
                        )

                        current = self.current_card_item()

                        if (
                            current
                            and current["id"] == vid
                        ):
                            self.detail_thumb.configure(
                                image=photo,
                            )

                elif kind == "catalog_metadata":
                    _, vid, data = event
                    item = self.items_by_id.get(vid)
                    if item is not None:
                        rating = data.get("rating")
                        if normalize_rating(
                            rating
                        ) is not None:
                            item["short_rating"] = str(
                                rating
                            )
                            self.update_card_rating(
                                item,
                                rating,
                            )
                        current = self.current_card_item()
                        if current and current.get("id") == vid:
                            self.apply_metadata(current, data)

                elif kind == "catalog_metadata_security":
                    self.set_left_status(
                        "Weryfikacja zabezpieczeń CDA",
                        "Oceny i IMDb w tle wstrzymane",
                        loading=False,
                    )

                elif kind == "security_verification":
                    _, state, url = event
                    if state in ("start", "native_hidden"):
                        self.set_left_status(
                            "Weryfikacja zabezpieczeń CDA…",
                            "WebView działa w tle" if state == "native_hidden" else "Czekam na potwierdzenie sesji",
                            loading=True,
                        )
                    elif state == "native_interactive":
                        self.set_left_status(
                            "CDA wymaga potwierdzenia",
                            "Weryfikacja jest w oknie CDA Free Player",
                            loading=True,
                        )
                    elif state == "required":
                        self.set_left_status(
                            "Weryfikacja zabezpieczeń CDA",
                            "Dane dodatkowe czekają na aktywną sesję",
                            loading=False,
                        )
                    elif state == "done":
                        self.set_left_status(
                            "Weryfikacja zakończona",
                            "Wznawiam oceny katalogu",
                            loading=False,
                        )
                        self.catalog_meta_gate.set()
                        self.queue_missing_catalog_metadata()

                elif kind == "metadata":
                    _, token, vid, data = event

                    if token == self.meta_token:
                        current = self.current_card_item()

                        if (
                            current
                            and current["id"] == vid
                        ):
                            self.apply_metadata(
                                current,
                                data,
                            )

                elif kind == "metadata_challenge":
                    _, token, vid = event

                    if token == self.meta_token:
                        current = self.current_card_item()

                        if (
                            current
                            and current["id"] == vid
                        ):
                            self.detail_loading.configure(
                                text="Weryfikacja zabezpieczeń CDA wymagana.",
                            )

                elif kind == "metadata_error":
                    _, token, vid, error = event

                    if token == self.meta_token:
                        current = self.current_card_item()

                        if (
                            current
                            and current["id"] == vid
                        ):
                            self.detail_loading.configure(
                                text="Nie udało się pobrać pełnego opisu.",
                            )

                elif kind == "metadata_direct":
                    _, vid, data = event
                    current = self.current_card_item()

                    if (
                        current
                        and current["id"] == vid
                    ):
                        self.apply_metadata(
                            current,
                            data,
                        )

                elif kind == "comments":
                    _, vid, panel, comments = event

                    try:
                        if panel.win.winfo_exists():
                            panel.set_comments(
                                comments
                            )
                    except Exception:
                        pass

                    current = self.current_card_item()

                    if (
                        current
                        and current["id"]
                        == vid
                    ):
                        self.detail_comments_btn.configure(
                            text=self.comments_button_text(
                                len(comments)
                            )
                        )

                elif kind == "comments_error":
                    _, vid, panel, error = event
                    try:
                        if panel.win.winfo_exists():
                            panel.set_comments_error(error)
                    except Exception:
                        pass

                elif kind == "status":
                    self.status.configure(
                        text=event[1],
                    )

                elif kind == "error":
                    self.status.configure(
                        text=event[1],
                    )

        except queue.Empty:
            pass

        self.root.after(
            60,
            self.pump,
        )
