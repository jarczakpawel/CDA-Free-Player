import tkinter as tk


class DPadController:
    def __init__(self, root, on_focus, on_debug, on_back, on_ok=None):
        self.root = root
        self.on_focus = on_focus
        self.on_debug = on_debug
        self.on_back = on_back
        self.on_ok = on_ok

        self.zones = {}
        self.zone = None
        self.index = 0
        self.last_key = "-"

        self.tag = "CDA_DPAD"
        self.root.bind_class(self.tag, "<KeyPress>", self._key, add=False)

    def register(self, zone, widgets, columns=1):
        self.zones[zone] = {
            "widgets": list(widgets),
            "columns": max(1, columns),
        }
        for widget in widgets:
            self._install_tag(widget)

    def register_widget(self, widget):
        self._install_tag(widget)

    def _install_tag(self, widget):
        tags = list(widget.bindtags())
        if self.tag not in tags:
            widget.bindtags((self.tag, *tags))

    def focus(self, zone, index=0):
        data = self.zones.get(zone)
        if not data or not data["widgets"]:
            return

        index = max(0, min(index, len(data["widgets"]) - 1))
        old_zone, old_index = self.zone, self.index

        self.zone = zone
        self.index = index

        widget = data["widgets"][index]
        try:
            widget.focus_force()
        except tk.TclError:
            try:
                widget.focus_set()
            except tk.TclError:
                pass

        self.on_focus(old_zone, old_index, zone, index, widget)
        self._debug()

    def current_widget(self):
        data = self.zones.get(self.zone)
        if not data or not data["widgets"]:
            return None
        if self.index >= len(data["widgets"]):
            return None
        return data["widgets"][self.index]

    def _debug(self):
        self.on_debug(self.zone or "-", self.index, self.last_key)

    def _key(self, event):
        key = event.keysym
        self.last_key = key
        self._debug()

        if self.zone == "search_entry":
            if key in ("Up", "Down", "Return", "KP_Enter", "Escape"):
                pass
            elif key == "BackSpace":
                return None
            elif key in ("Left", "Right", "Home", "End", "Delete"):
                return None
            elif event.char and event.char.isprintable():
                return None

        if key == "Left":
            self.left()
            return "break"
        if key == "Right":
            self.right()
            return "break"
        if key == "Up":
            self.up()
            return "break"
        if key == "Down":
            self.down()
            return "break"
        if key in ("Return", "KP_Enter"):
            widget = self.current_widget()

            if self.on_ok is not None:
                handled = self.on_ok(
                    self.zone,
                    self.index,
                    widget,
                )
                if handled:
                    return "break"

            if widget is not None:
                try:
                    widget.invoke()
                except (tk.TclError, AttributeError):
                    try:
                        widget.event_generate("<<DPadOK>>")
                    except tk.TclError:
                        pass

            return "break"
        if key in ("BackSpace", "Escape"):
            self.on_back()
            return "break"

        return None

    def left(self):
        if self.zone == "years":
            if self.index > 0:
                self.focus("years", self.index - 1)
            else:
                self.focus("left", 0)
            return

        if self.zone in ("sort", "duration"):
            if self.index > 0:
                self.focus(self.zone, self.index - 1)
            else:
                self.focus("left", 0)
            return

        if self.zone == "cards":
            if self._collection_mode():
                target = self._card_horizontal_neighbor(-1)
                if target is not None:
                    self.focus("cards", target)
                else:
                    self.focus("left", self._section_left_index())
            else:
                cols = self.zones["cards"]["columns"]
                if self.index % cols:
                    self.focus("cards", self.index - 1)
                else:
                    self.focus("left", self._section_left_index())
            return

        if self.zone == "collection_action":
            self.focus("left", self._section_left_index())
            return

        if self.zone == "detail_actions":
            if self.index > 0:
                self.focus("detail_actions", self.index - 1)
            else:
                self.focus("left", self._section_left_index())
            return

        if self.zone == "left":
            return

        if self.zone == "search_entry":
            return

    def right(self):
        if self.zone == "detail_actions":
            widgets = self.zones["detail_actions"]["widgets"]
            if self.index + 1 < len(widgets):
                self.focus("detail_actions", self.index + 1)
                return
            card_index = getattr(self.root, "_cda_last_card_index", 0)
            if self.zones.get("cards", {}).get("widgets"):
                self.focus("cards", card_index)
            return

        if self.zone == "left":
            if self._collection_mode():
                if self.zones.get("collection_action", {}).get("widgets"):
                    self.focus("collection_action", 0)
                elif self.zones.get("cards", {}).get("widgets"):
                    self.focus("cards", 0)
            else:
                self.focus("years", self._selected_year_index())
            return

        if self.zone == "years":
            widgets = self.zones["years"]["widgets"]
            if self.index + 1 < len(widgets):
                self.focus("years", self.index + 1)
            return

        if self.zone in ("sort", "duration"):
            widgets = self.zones[self.zone]["widgets"]
            if self.index + 1 < len(widgets):
                self.focus(self.zone, self.index + 1)
            return

        if self.zone == "cards":
            if self._collection_mode():
                target = self._card_horizontal_neighbor(1)
                if target is not None:
                    self.focus("cards", target)
            else:
                cols = self.zones["cards"]["columns"]
                widgets = self.zones["cards"]["widgets"]
                if self.index % cols < cols - 1 and self.index + 1 < len(widgets):
                    self.focus("cards", self.index + 1)
            return

        if self.zone == "search_entry":
            return

        if self.zone == "collection_action":
            if self.zones.get("cards", {}).get("widgets"):
                self.focus("cards", 0)
            return

    def up(self):
        if self.zone == "detail_actions":
            return

        if self.zone == "left":
            if self.index > 0:
                self.focus("left", self.index - 1)
            return

        if self.zone == "search_entry":
            self.focus("left", 2)
            return

        if self.zone == "years":
            self.focus("left", 3)
            return

        if self.zone == "collection_action":
            self.focus("left", self._section_left_index())
            return

        if self.zone == "sort":
            self.focus("years", self._selected_year_index())
            return

        if self.zone == "duration":
            self.focus("sort", self._selected_sort_index())
            return

        if self.zone == "cards":
            if self._collection_mode():
                target = self._card_vertical_neighbor(-1)
                if target is not None:
                    self.focus("cards", target)
                else:
                    self.focus("collection_action", 0)
            else:
                cols = self.zones["cards"]["columns"]
                if self.index >= cols:
                    self.focus("cards", self.index - cols)
                else:
                    self.focus("duration", self._selected_duration_index())

    def down(self):
        if self.zone == "detail_actions":
            return

        if self.zone == "left":
            widgets = self.zones["left"]["widgets"]
            if self.index == 2 and "search_entry" in self.zones:
                self.focus("search_entry", 0)
            elif self.index + 1 < len(widgets):
                self.focus("left", self.index + 1)
            return

        if self.zone == "search_entry":
            self.focus("left", min(3, len(self.zones["left"]["widgets"]) - 1))
            return

        if self.zone == "years":
            self.focus("sort", self._selected_sort_index())
            return

        if self.zone == "sort":
            self.focus("duration", self._selected_duration_index())
            return

        if self.zone == "duration":
            if self.zones.get("cards", {}).get("widgets"):
                self.focus("cards", 0)
            return

        if self.zone == "collection_action":
            if self.zones.get("cards", {}).get("widgets"):
                self.focus("cards", 0)
            return

        if self.zone == "cards":
            if self._collection_mode():
                target = self._card_vertical_neighbor(1)
                if target is not None:
                    self.focus("cards", target)
            else:
                cols = self.zones["cards"]["columns"]
                widgets = self.zones["cards"]["widgets"]
                target = self.index + cols
                if target < len(widgets):
                    self.focus("cards", target)
                elif widgets:
                    self.focus("cards", len(widgets) - 1)

    def _card_horizontal_neighbor(self, direction):
        positions = getattr(self.root, "_cda_card_positions", [])
        if not (0 <= self.index < len(positions)):
            return None
        row, col = positions[self.index]
        target_col = col + direction
        for i, (r, c) in enumerate(positions):
            if r == row and c == target_col:
                return i
        return None

    def _card_vertical_neighbor(self, direction):
        positions = getattr(self.root, "_cda_card_positions", [])
        if not (0 <= self.index < len(positions)):
            return None
        row, col = positions[self.index]
        rows = sorted({r for r, _ in positions if (r < row if direction < 0 else r > row)}, reverse=direction < 0)
        if not rows:
            return None
        target_row = rows[0]
        candidates = [(abs(c - col), i) for i, (r, c) in enumerate(positions) if r == target_row]
        return min(candidates)[1] if candidates else None

    def _collection_mode(self):
        return getattr(self.root, "_cda_view_mode", "search") in ("recent", "favorites")

    def _section_left_index(self):
        mode = getattr(self.root, "_cda_view_mode", "search")
        if mode == "recent":
            return 1
        if mode == "favorites":
            return 2
        return 0

    def _selected_year_index(self):
        return getattr(self.root, "_cda_year_index", 0)

    def _selected_sort_index(self):
        return getattr(self.root, "_cda_sort_index", 0)

    def _selected_duration_index(self):
        return getattr(self.root, "_cda_duration_index", 0)
