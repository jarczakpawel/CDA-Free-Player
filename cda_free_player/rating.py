import math
import tkinter as tk
from PIL import Image, ImageDraw, ImageTk

YELLOW = "#ffd22e"
WHITE = "#f7f7f7"


def normalize_rating(value):
    if value is None:
        return None

    text = str(value).strip()

    if not text:
        return None

    try:
        value = float(
            text.replace(",", ".")
        )
    except Exception:
        return None

    return max(
        0.0,
        min(
            5.0,
            value,
        ),
    )


def _star_points(cx, cy, outer, inner):
    points = []
    for i in range(10):
        angle = -math.pi / 2 + i * math.pi / 5
        radius = outer if i % 2 == 0 else inner
        points.append((cx + math.cos(angle) * radius, cy + math.sin(angle) * radius))
    return points


def render_stars(value, star_size=14, gap=2, bg="#20242c", scale=4):
    rating = normalize_rating(value)
    width = star_size * 5 + gap * 4
    height = star_size
    W, H = width * scale, height * scale
    image = Image.new(
        "RGBA",
        (W, H),
        bg,
    )

    if rating is None:
        return image.resize(
            (width, height),
            Image.Resampling.LANCZOS,
        )
    white = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    yellow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    white_draw = ImageDraw.Draw(white)
    yellow_draw = ImageDraw.Draw(yellow)
    size = star_size * scale
    gap_px = gap * scale
    outer = size * 0.48
    inner = outer * 0.46
    cy = H / 2
    for i in range(5):
        x0 = i * (size + gap_px)
        cx = x0 + size / 2
        points = _star_points(cx, cy, outer, inner)
        white_draw.polygon(points, fill=WHITE)
        fraction = max(0.0, min(1.0, rating - i))
        if fraction > 0:
            star_layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
            sd = ImageDraw.Draw(star_layer)
            sd.polygon(points, fill=YELLOW)
            crop_right = int(x0 + size * fraction)
            if crop_right > x0:
                clip = Image.new("L", (W, H), 0)
                ImageDraw.Draw(clip).rectangle((int(x0), 0, crop_right, H), fill=255)
                yellow.alpha_composite(Image.composite(star_layer, Image.new("RGBA", (W,H),(0,0,0,0)), clip))
    image.alpha_composite(white)
    image.alpha_composite(yellow)
    return image.resize((width, height), Image.Resampling.LANCZOS)


class StarRatingView(tk.Label):
    def __init__(
        self,
        parent,
        rating=None,
        star_size=14,
        gap=2,
        bg="#20242c",
        **kwargs,
    ):
        self.rating = normalize_rating(
            rating
        )
        self.star_size = star_size
        self.gap = gap
        self._bg = bg
        self._photo = None
        super().__init__(
            parent,
            bg=bg,
            bd=0,
            highlightthickness=0,
            **kwargs,
        )
        self.redraw()

    @property
    def has_rating(self):
        return self.rating is not None

    def set_rating(self, value):
        value = normalize_rating(
            value
        )

        if (
            value is None
            and self.rating is None
            and self._photo is None
        ):
            return

        if (
            value is not None
            and self.rating is not None
            and abs(
                value - self.rating
            ) < 0.001
            and self._photo is not None
        ):
            return

        self.rating = value
        self.redraw()

    def set_background(self, bg):
        if bg == self._bg:
            return

        self._bg = bg
        self.configure(
            bg=bg
        )
        self.redraw()

    def redraw(self):
        if self.rating is None:
            self._photo = None
            self.configure(
                image="",
                text="",
            )
            return

        image = render_stars(
            self.rating,
            self.star_size,
            self.gap,
            self._bg,
        )
        self._photo = ImageTk.PhotoImage(
            image
        )
        self.configure(
            image=self._photo,
            text="",
        )
