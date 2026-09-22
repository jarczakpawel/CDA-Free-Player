import queue
import shutil
import re
import threading
from concurrent.futures import ThreadPoolExecutor
import time
import tkinter as tk
from tkinter import messagebox
from hashlib import sha1
from io import BytesIO

import httpx
from PIL import Image, ImageDraw, ImageTk

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
    CACHE_TTL,
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
    THUMB_CACHE_MAX,
    THUMB_CACHE_FREE_RESERVE,
    THUMB_TOUCH_INTERVAL,
    LOG_FILE,
    APP_ICON,
)
from .controller import DPadController
from .database import Database
from .player import Player
from .rating import StarRatingView, normalize_rating
from .movie_panel import MovieInfoWindow
from .settings_window import SettingsWindow
from .widgets import FlatButton


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
        self.closed = False
        self.play_preparing = False
        self.play_cancel = threading.Event()
        self.thumbnail_pool = ThreadPoolExecutor(max_workers=4, thread_name_prefix="cda-thumb")
        self.thumbnail_cache_bytes = -1
        self.thumbnail_prune_pending = False
        self.thumbnail_prune_lock = threading.Lock()
        self.schedule_thumbnail_prune(force=True)
        self.root.protocol("WM_DELETE_WINDOW", self.close)
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
        self.card_positions = []
        self.root._cda_card_positions = self.card_positions
        self.card_rating_views = {}
        self.card_footer_frames = {}
        self.photos = {}
        self.comments_memory = {}
        self.metadata_memory = {}
        self.movie_windows = {}


        self.year_buttons = {}
        self.sort_buttons = {}
        self.duration_buttons = {}
        self.left_buttons = []

        self.last_search = None
        self.browse_state = None
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
        self.detail_icons = self.build_detail_icons()

        self.build()

        self.controller = DPadController(
            self.root,
            self.on_controller_focus,
            None,
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
        self.root._cda_view_mode = "search"

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

    def build_detail_icons(self):
        def photo(kind, filled=False):
            import math
            image = Image.new("RGBA", (28, 28), (0, 0, 0, 0))
            draw = ImageDraw.Draw(image)
            color = (242, 242, 242, 255)
            if kind == "favorite":
                points = []
                for i in range(81):
                    t = 2 * math.pi * i / 80
                    x = 14 + 10.2 * (math.sin(t) ** 3)
                    y = 13 - 8.4 * (math.cos(t) - 0.42 * math.cos(2 * t) - 0.2 * math.cos(3 * t) - 0.08 * math.cos(4 * t))
                    points.append((x, y))
                if filled:
                    draw.polygon(points, fill=(255, 97, 125, 255))
                else:
                    draw.line(points + [points[0]], fill=color, width=2, joint="curve")
            elif kind == "info":
                draw.ellipse((4, 4, 24, 24), outline=color, width=2)
                draw.ellipse((13, 8, 15, 10), fill=color)
                draw.rounded_rectangle((12, 12, 16, 21), radius=2, fill=color)
            else:
                draw.rounded_rectangle((4, 5, 24, 21), radius=4, outline=color, width=2)
                draw.polygon([(9, 20), (7, 25), (14, 21)], fill=color)
                draw.line((9, 10, 19, 10), fill=color, width=2)
                draw.line((9, 15, 19, 15), fill=color, width=2)
            return ImageTk.PhotoImage(image)
        return {
            "favorite": photo("favorite"),
            "favorite_on": photo("favorite", True),
            "info": photo("info"),
            "comments": photo("comments"),
        }

    def tv_button(
        self,
        parent,
        text,
        command=None,
        width=None,
    ):
        return FlatButton(
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

        self.browse_controls = tk.Frame(
            self.main,
            bg=BG,
        )
        self.browse_controls.pack(
            fill="x",
            padx=14,
            pady=(12, 6),
        )

        tk.Label(
            self.browse_controls,
            text="Rok",
            bg=BG,
            fg=MUTED,
            font=("Sans", 10, "bold"),
        ).pack(anchor="w")

        year_wrap = tk.Frame(
            self.browse_controls,
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
            self.browse_controls,
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

        self.header = tk.Frame(
            self.main,
            bg=BG,
        )
        self.header.pack(
            fill="x",
            padx=14,
            pady=(4, 5),
        )

        self.results_title = tk.Label(
            self.header,
            text="",
            bg=BG,
            fg=TEXT,
            font=("Sans", 17, "bold"),
        )
        self.results_title.pack(side="left")

        self.remove_collection_button = FlatButton(
            self.header,
            text="",
            command=self.toggle_remove_mode,
            bg="#3a1c22", fg="#ff8b97", activebackground="#6b202b", activeforeground=TEXT,
            relief="flat", bd=0, padx=9, pady=4, font=("Sans", 9, "bold"),
            takefocus=True,
        )

        self.count_label = tk.Label(
            self.header,
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
            width=1,
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

        browse = self.tv_button(
            self.nav_panel,
            "Przeglądaj",
            lambda: self.show_browse(True),
        )
        browse.pack(
            fill="x",
            padx=12,
            pady=4,
        )

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
            browse,
            recent,
            favorite,
            search_button,
        ]
        self.browse_button = browse
        self.recent_button = recent
        self.favorite_button = favorite
        self.search_button = search_button


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
            width=1,
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
            width=1,
        )
        self.left_status_sub.pack(
            fill="x",
        )

        self.mouse_pad_toggle = FlatButton(
            self.left_status_frame,
            text="PAD",
            command=self.toggle_mouse_pad,
            bg="#1b2633", fg=TEXT, activebackground=SELECTED, activeforeground=TEXT,
            relief="flat", bd=0, padx=7, pady=4, font=("Sans", 8, "bold"),
            takefocus=False,
        )
        self.mouse_pad_toggle.pack(side="right", padx=(4, 8), pady=10)

        self.settings_toggle = FlatButton(
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
            padx=8, pady=8,
        )
        def arrow(text, row, col, direction):
            b = FlatButton(
                self.mouse_pad_popup, text=text,
                command=lambda d=direction: self.desktop_pad_move(d),
                width=5, height=2, bg=PANEL2, fg=TEXT, activebackground=SELECTED,
                activeforeground=TEXT, relief="flat", bd=0, font=("Sans", 16, "bold"),
                takefocus=False,
            )
            b.grid(row=row, column=col, padx=3, pady=3, sticky="nsew")
        arrow("↑", 0, 1, "up")
        arrow("←", 1, 0, "left")
        arrow("↓", 1, 1, "down")
        arrow("→", 1, 2, "right")
        back = FlatButton(
            self.mouse_pad_popup, text="BACK", command=self.on_controller_back,
            bg=PANEL2, fg=TEXT, activebackground=SELECTED, activeforeground=TEXT,
            relief="flat", bd=0, padx=10, pady=8, font=("Sans", 11, "bold"),
            takefocus=False, anchor="center",
        )
        back.grid(row=2, column=0, columnspan=3, padx=3, pady=(6, 3), sticky="ew")

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
            pady=(3, 6),
        )

        def detail_icon(image, command):
            button = FlatButton(
                self.detail_actions,
                text="",
                image=image,
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
                width=46,
                height=40,
                takefocus=True,
            )
            button.pack(side="left", padx=(0, 6))
            return button

        self.detail_favorite_btn = detail_icon(self.detail_icons["favorite"], self.toggle_favorite_current)
        self.detail_description_btn = detail_icon(self.detail_icons["info"], lambda: self.open_movie_panel("description"))
        self.detail_comments_btn = detail_icon(self.detail_icons["comments"], lambda: self.open_movie_panel("comments"))
        self.detail_action_buttons = [self.detail_favorite_btn, self.detail_description_btn, self.detail_comments_btn]

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
            columns=3,
        )
        self.controller.register(
            "collection_action",
            [self.remove_collection_button],
            columns=1,
        )

        self.bind_mouse_zone(
            "detail_actions",
            self.detail_action_buttons,
        )
        self.bind_mouse_zone("collection_action", [self.remove_collection_button])
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
            "collection_action",
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
        if self.play_preparing:
            self.play_cancel.set()
            self.play_preparing = False
            self.status.configure(text="Przygotowanie filmu przerwane")
            return
        if self.loading_page:
            self.cancel_active_search("Backspace", invalidate=True)

        if self.controller.zone == "cards":
            self.show_left_detail()
            self.controller.focus("detail_actions", 0)
            return

        if self.controller.zone == "detail_actions":
            self.show_left_nav()
            self.controller.focus("left", self.section_left_index())
            return

        if self.controller.zone == "collection_action":
            self.show_left_nav()
            self.controller.focus("left", self.section_left_index())
            return

        if self.controller.zone == "left":
            if self.view_mode in ("recent", "favorites"):
                self.show_browse(False)
                self.controller.focus("left", 0)
            else:
                self.confirm_close()
            return

        self.show_left_nav()
        self.controller.focus("left", self.section_left_index())

    def section_left_index(self):
        if self.view_mode == "recent":
            return 1
        if self.view_mode == "favorites":
            return 2
        return 0

    def confirm_close(self):
        if messagebox.askyesno(
            "Zamknąć aplikację?",
            "Czy na pewno chcesz zamknąć CDA Free Player?",
            parent=self.root,
        ):
            self.close(force=True)


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

                                                  
        edge_margin = 8.0

        target_left = None

                       
                                                                      
                                                                 
                                         
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
                                                                    
        self.hide_loading_indicator()

        self.items.clear()
        self.items_by_id.clear()
        self.card_buttons.clear()
        self.card_frames.clear()
        self.card_positions.clear()
        self.card_footer_frames.clear()
        self.photos.clear()

        for child in self.grid_frame.winfo_children():
                                                                     
            if (
                self.grid_loading_exists()
                and child == self.grid_loading
            ):
                continue

            try:
                child.destroy()
            except tk.TclError:
                pass

                                                                               
        self.create_grid_loading()

        self.result_canvas.yview_moveto(0)
        self.refresh_card_zone()
        self.show_left_nav()

    def set_browse_controls_visible(self, visible):
        if visible:
            if not self.browse_controls.winfo_manager():
                self.browse_controls.pack(
                    fill="x", padx=14, pady=(12, 6), before=self.header
                )
        else:
            self.browse_controls.pack_forget()

    def capture_browse_state(self):
        if self.view_mode != "search":
            return
        self.browse_state = {
            "query": self.query,
            "label": self.query_label,
            "year": self.selected_year,
            "sort": self.sort_key,
            "duration": self.duration_key,
            "next_page": self.next_page,
            "exhausted": self.exhausted,
            "initial_pages": self.initial_pages,
            "empty_pages_skipped": self.empty_pages_skipped,
            "items": [item.copy() for item in self.items],
            "card_index": self.last_card_index,
        }

    def show_browse(self, focus_cards=False):
        state = self.browse_state
        if state is None:
            if self.view_mode != "search":
                self.start_search(self.query, self.query_label, self.selected_year)
            if focus_cards and self.card_buttons:
                self.controller.focus("cards", min(self.last_card_index, len(self.card_buttons) - 1))
            return

        self.search_cancel.set()
        self.search_token += 1
        self.search_cancel = threading.Event()
        self.view_mode = "search"
        self.root._cda_view_mode = "search"
        self.set_remove_button(None)
        self.set_browse_controls_visible(True)
        self.query = state["query"]
        self.query_label = state["label"]
        self.selected_year = state["year"]
        self.sort_key = state["sort"]
        self.duration_key = state["duration"]
        self.next_page = state["next_page"]
        self.exhausted = state["exhausted"]
        self.initial_pages = state["initial_pages"]
        self.empty_pages_skipped = state["empty_pages_skipped"]
        self.loading_page = False
        self.clear_results()
        self.results_title.configure(text=self.query_label)
        self.add_items([item.copy() for item in state["items"]])
        self.paint_filters()
        self.last_card_index = max(0, min(state["card_index"], len(self.card_buttons) - 1)) if self.card_buttons else 0
        self.root._cda_last_card_index = self.last_card_index
        if not self.card_buttons and not self.exhausted:
            self.load_next_page()
        if focus_cards and self.card_buttons:
            self.controller.focus("cards", self.last_card_index)
        else:
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
        self.play_cancel.set()
        self.play_preparing = False
        if self.view_mode == "search":
            self.save_last_search()

        self.view_mode = "search"
        self.root._cda_view_mode = "search"
        self.browse_state = None
        self.set_remove_button(None)
        self.set_browse_controls_visible(True)
        self.query = query
        self.query_label = label

        if year is not None:
            self.selected_year = year
            self.root._cda_year_index = (
                year - 1950
            )

                                                                      
                                                                              
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
                cached["items"],
                "cache",
                cached["stats"],
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
                {"items": videos, "stats": stats},
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

    def add_items(self, videos):
        sectioned = self.view_mode in ("recent", "favorites")
        row_cursor = 0
        col_cursor = 0
        last_section = None
        for item in videos:
            if item["id"] in self.items_by_id:
                continue

            index = len(self.items)
            self.items.append(item)
            self.items_by_id[item["id"]] = item
            if sectioned:
                section = item.get("section_label") or ""
                if section and section != last_section:
                    if col_cursor:
                        row_cursor += 1
                        col_cursor = 0
                    tk.Label(
                        self.grid_frame,
                        text=section,
                        bg=BG,
                        fg=TEXT,
                        font=("Sans", 12, "bold"),
                        anchor="w",
                    ).grid(
                        row=row_cursor, column=0, columnspan=GRID_COLS,
                        padx=7, pady=(12 if row_cursor else 4, 3), sticky="ew"
                    )
                    row_cursor += 1
                    last_section = section
                row = row_cursor
                col = col_cursor
                col_cursor += 1
                if col_cursor >= GRID_COLS:
                    col_cursor = 0
                    row_cursor += 1
            else:
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
            self.card_positions.append((row, col))

            button_height = 148 if self.view_mode == "recent" else 158
            button = FlatButton(
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
            self.add_progress_bar(card, item)

            self.bind_card_mouse(card, index)
            self.card_frames.append(card)
            self.card_buttons.append(button)
            self.card_footer_frames[item["id"]] = footer
            self.load_thumb(item, button, index)

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
        bar = tk.Canvas(card, width=1, height=4, bg="#3c434e", highlightthickness=0, bd=0)
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
                now = time.time()
                if now - path.stat().st_mtime > THUMB_TOUCH_INTERVAL:
                    try:
                        path.touch()
                    except OSError:
                        pass
                image = Image.open(path).convert("RGB")
                image.thumbnail((210, 118))
                photo = ImageTk.PhotoImage(image)
                self.photos[item["id"]] = photo
                button.configure(image=photo)
                return
            except Exception:
                pass

        self.thumbnail_pool.submit(self.thumb_worker, item["id"], item["image"], path, index)

    def thumb_worker(
        self,
        vid,
        url,
        path,
        index,
    ):
        if self.closed:
            return
        try:
            response = httpx.get(
                url,
                timeout=10,
                follow_redirects=True,
            )
            response.raise_for_status()

            image = Image.open(
                BytesIO(response.content),
            ).convert("RGB")
            image.thumbnail((210, 118))

            temporary = path.with_name(f"{path.name}.{threading.get_ident()}.tmp")
            try:
                image.save(temporary, format="JPEG", quality=86)
                temporary.replace(path)
            finally:
                try:
                    temporary.unlink(missing_ok=True)
                except OSError:
                    pass

            if self.thumbnail_cache_bytes >= 0:
                try:
                    self.thumbnail_cache_bytes += path.stat().st_size
                except OSError:
                    pass
            self.schedule_thumbnail_prune()

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

    def set_detail_metadata(self, data):
        data = data or {}
        self.set_detail_rating(data.get("rating"))
        parts = []
        votes = data.get("cda_votes")
        if votes is not None:
            parts.append(f"CDA • {votes} ocen")
        imdb = data.get("imdb_rating")
        if imdb:
            text = f"IMDb {imdb} / 10"
            imdb_votes = data.get("imdb_votes")
            if imdb_votes is not None:
                text += f" • {imdb_votes} głosów"
            parts.append(text)
        self.detail_source_ratings.configure(text="   ".join(parts))

    def update_card_rating(self, item, rating):
        return

    def show_movie_details(self, item):
        self.show_left_detail()
        self.detail_title.configure(text=item["title"])

        cached_meta = self.metadata_memory.get(item["id"])
        self.set_detail_metadata(cached_meta)

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

        cached_comments = self.comments_memory.get(item["id"])
        initial_count = (
            len(cached_comments)
            if cached_comments is not None
            else None
        )
        self.detail_comments_btn.configure(image=self.detail_icons["comments"], text="")
        self.detail_favorite_btn.configure(
            image=self.detail_icons["favorite_on"] if self.db.is_favorite(item["id"]) else self.detail_icons["favorite"],
            text="",
        )

        short = item.get("short_description", "").strip() or "Brak skróconego opisu na liście."
        self.set_detail_text(short)
        photo = self.photos.get(item["id"])
        self.detail_thumb.configure(image=photo or self.placeholder)

                                                                               
                                                                              
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
        metadata = dict(self.metadata_memory.get(item["id"]) or {})
        if not metadata.get("description"):
            metadata["description"] = item.get("short_description", "")
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
        else:
            cached_full = self.metadata_memory.get(item["id"])
            if cached_full and cached_full.get("description"):
                panel.set_metadata(cached_full)
                panel.set_description(cached_full.get("description"))
            else:
                panel.set_description_loading()
                threading.Thread(
                    target=self.description_worker,
                    args=(item.copy(), panel),
                    daemon=True,
                ).start()

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
            self.detail_favorite_btn.configure(image=self.detail_icons["favorite_on"] if state else self.detail_icons["favorite"], text="")
        self.status.configure(text="Dodano do ulubionych." if state else "Usunięto z ulubionych.")
        return state

    def description_worker(self, item, panel):
        try:
            text, source = self.client.get_html(item["url"], True)
            data, _ = parse_metadata(text)
            comments = parse_comments(text)
            parsed_comment_count = data.get("comment_count")
            if parsed_comment_count is None and comments:
                data["comment_count"] = len(comments)
            cached_comments = comments if comments or parsed_comment_count is not None else None
            self.events.put((
                "panel_description",
                item["id"],
                panel,
                data,
                cached_comments,
                data.get("description") or item.get("short_description", ""),
            ))
            log_event(
                "description",
                id=item["id"],
                source=source,
                description_len=len(data.get("description") or ""),
            )
        except Exception as exc:
            self.events.put((
                "panel_description_error",
                item["id"],
                panel,
                f"Nie udało się pobrać pełnego opisu: {exc}",
            ))

    def load_comments_for_panel(self, item, panel):
        cached = self.comments_memory.get(item["id"])
        if cached is not None:
            meta = self.metadata_memory.get(item["id"])
            if meta:
                panel.set_metadata(meta)
            panel.set_comments(
                cached
            )

            current = self.current_card_item()

            if (
                current
                and current["id"]
                == item["id"]
            ):
                self.detail_comments_btn.configure(image=self.detail_icons["comments"], text="")

            return
        threading.Thread(
            target=self.comments_worker,
            args=(item.copy(), panel),
            daemon=True,
        ).start()

    def comments_worker(self, item, panel):
        try:
            text, source = self.client.get_html(item["url"], True)
            if not text:
                self.events.put(("comments_error", item["id"], panel, "Komentarze wymagają aktywnej sesji CDA."))
                return
            comments = parse_comments(text)
            data, _ = parse_metadata(text)
            self.events.put((
                "comments",
                item["id"],
                panel,
                comments,
                data,
            ))
            log_event("comments", id=item["id"], source=source, count=len(comments))
        except Exception as exc:
            self.events.put(("comments_error", item["id"], panel, f"Nie udało się pobrać komentarzy: {exc}"))

    def open_settings(self):
        SettingsWindow(
            self.root,
            self.db,
            on_data_changed=self.refresh_after_settings,
            on_cache_clear=self.clear_transient_cache,
        )

    def remember_metadata(self, vid, data):
        current = dict(self.metadata_memory.get(vid) or {})
        for key, value in (data or {}).items():
            if value is not None and value != "" and value != []:
                current[key] = value
        self.metadata_memory.pop(vid, None)
        self.metadata_memory[vid] = current
        while len(self.metadata_memory) > 24:
            self.metadata_memory.pop(next(iter(self.metadata_memory)))
        return current

    def remember_comments(self, vid, comments):
        self.comments_memory.pop(vid, None)
        self.comments_memory[vid] = comments
        while len(self.comments_memory) > 12:
            self.comments_memory.pop(next(iter(self.comments_memory)))

    def schedule_thumbnail_prune(self, force=False):
        if self.closed:
            return
        try:
            free = shutil.disk_usage(THUMB_DIR).free
        except OSError:
            free = THUMB_CACHE_FREE_RESERVE
        needs = force or self.thumbnail_cache_bytes > THUMB_CACHE_MAX or free < THUMB_CACHE_FREE_RESERVE
        if not needs:
            return
        with self.thumbnail_prune_lock:
            if self.thumbnail_prune_pending:
                return
            self.thumbnail_prune_pending = True
        self.thumbnail_pool.submit(self.prune_thumbnail_cache)

    def prune_thumbnail_cache(self):
        try:
            now = time.time()
            cutoff = now - CACHE_TTL
            files = [p for p in THUMB_DIR.iterdir() if p.is_file()]
            total = 0
            kept = []
            for path in files:
                try:
                    st = path.stat()
                    if st.st_mtime < cutoff:
                        path.unlink()
                        continue
                    total += st.st_size
                    kept.append((st.st_mtime, st.st_size, path))
                except OSError:
                    pass

            try:
                free = shutil.disk_usage(THUMB_DIR).free
                safe_budget = max(0, free + total - THUMB_CACHE_FREE_RESERVE)
                limit = min(THUMB_CACHE_MAX, safe_budget)
            except OSError:
                limit = THUMB_CACHE_MAX

            if total > limit:
                target = limit if limit <= 48 * 1024 * 1024 else max(48 * 1024 * 1024, limit * 7 // 8)
                protect_after = now - 10 * 60
                kept.sort(key=lambda x: x[0])
                for protect_recent in (True, False):
                    for mtime, size, path in kept:
                        if total <= target:
                            break
                        if protect_recent and mtime >= protect_after:
                            continue
                        try:
                            path.unlink()
                            total -= size
                        except OSError:
                            pass
                    if total <= target:
                        break
            try:
                self.thumbnail_cache_bytes = sum(
                    path.stat().st_size for path in THUMB_DIR.iterdir() if path.is_file()
                )
            except OSError:
                self.thumbnail_cache_bytes = max(0, total)
        except OSError:
            pass
        finally:
            with self.thumbnail_prune_lock:
                self.thumbnail_prune_pending = False

    def clear_transient_cache(self):
        self.metadata_memory.clear()
        self.comments_memory.clear()
        self.photos.clear()
        try:
            for path in THUMB_DIR.iterdir():
                if path.is_file():
                    path.unlink()
            self.thumbnail_cache_bytes = 0
        except OSError:
            pass
        self.set_detail_metadata(None)

    def refresh_after_settings(self):
        if self.view_mode == "recent":
            self.show_recent()
        elif self.view_mode == "favorites":
            self.show_favorites()
        else:
            for item in self.items:
                item["favorite"] = self.db.is_favorite(item["id"])
            self.clear_results()
                                                                                   
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
        self.play_cancel.set()
        self.play_preparing = False
        self.capture_browse_state()
        self.cancel_active_search(
            "Ostatnio oglądane",
            invalidate=True,
        )

        self.view_mode = "recent"
        self.root._cda_view_mode = "recent"
        self.set_browse_controls_visible(False)
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
        self.play_cancel.set()
        self.play_preparing = False
        self.capture_browse_state()
        self.cancel_active_search(
            "Ulubione",
            invalidate=True,
        )

        self.view_mode = "favorites"
        self.root._cda_view_mode = "favorites"
        self.set_browse_controls_visible(False)
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
        if self.play_preparing:
            return
        self.play_preparing = True
        self.play_cancel = threading.Event()
        self.status.configure(
            text=f"Przygotowanie: {item['title']}",
        )

        threading.Thread(
            target=self.play_worker,
            args=(item.copy(), self.play_cancel),
            daemon=True,
        ).start()

    def play_worker(self, item, cancel_event):
        try:
            page_html, source = self.client.get_html(
                item["url"],
                True,
                cancel_event=cancel_event,
                expect_player=True,
            )

            pdata = parse_player_data(
                page_html,
            )

            if not pdata:
                raise RuntimeError(
                    "Brak player_data.",
                )

            kind, quality, log_path = self.player.play(
                item,
                pdata,
                cancel_event,
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

            log_event(
                "play_page_source",
                id=item["id"],
                source=source,
                log=str(log_path),
            )
        except SearchCancelled:
            pass
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

        finally:
            self.events.put(("play_prepared", cancel_event))

    def close(self, force=False):
        if not force:
            self.confirm_close()
            return
        self.closed = True
        self.search_cancel.set()
        self.play_cancel.set()
        self.thumbnail_pool.shutdown(wait=False, cancel_futures=True)
        self.root.destroy()
        self.player.stop()
        if self.client._native_webview is not None:
            self.client._native_webview.stop()

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
        if self.closed: return
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
                            "Sesja CDA gotowa",
                            loading=False,
                        )

                elif kind == "panel_description":
                    _, vid, panel, data, comments, description = event
                    data = self.remember_metadata(vid, data)
                    if comments is not None:
                        self.remember_comments(vid, comments)
                    try:
                        if panel.win.winfo_exists():
                            panel.set_metadata(data)
                            panel.set_description(description)
                    except Exception:
                        pass
                    current = self.current_card_item()
                    if current and current["id"] == vid:
                        self.set_detail_metadata(data)

                elif kind == "panel_description_error":
                    _, vid, panel, error = event
                    try:
                        if panel.win.winfo_exists():
                            panel.set_description_error(error)
                    except Exception:
                        pass

                elif kind == "comments":
                    _, vid, panel, comments, data = event
                    self.remember_comments(vid, comments)
                    data = self.remember_metadata(vid, data)

                    try:
                        if panel.win.winfo_exists():
                            panel.set_metadata(data)
                            panel.set_comments(
                                comments
                            )
                    except Exception:
                        pass

                    current = self.current_card_item()
                    if current and current["id"] == vid:
                        self.set_detail_metadata(data)

                    current = self.current_card_item()

                    if (
                        current
                        and current["id"]
                        == vid
                    ):
                        self.detail_comments_btn.configure(image=self.detail_icons["comments"], text="")

                elif kind == "comments_error":
                    _, vid, panel, error = event
                    try:
                        if panel.win.winfo_exists():
                            panel.set_comments_error(error)
                    except Exception:
                        pass

                elif kind == "play_prepared":
                    if event[1] is self.play_cancel:
                        self.play_preparing = False

                elif kind == "play_end":
                    if self.view_mode == "recent":
                        self.show_recent()
                    self.show_left_nav()
                    self.controller.focus("left", self.section_left_index())
                    try:
                        self.root.lift()
                        self.root.focus_force()
                    except tk.TclError:
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
