import tkinter as tk


class FlatButton(tk.Label):
    def __init__(self, parent, command=None, activebackground=None, activeforeground=None, **kwargs):
        self.command = command
        self.activebackground = activebackground or kwargs.get("bg", kwargs.get("background", ""))
        self.activeforeground = activeforeground or kwargs.get("fg", kwargs.get("foreground", ""))
        self._normal_bg = kwargs.get("bg", kwargs.get("background", ""))
        self._normal_fg = kwargs.get("fg", kwargs.get("foreground", ""))
        self._state = kwargs.pop("state", "normal")
        if kwargs.get("width") is None:
            kwargs.pop("width", None)
        if kwargs.get("height") is None:
            kwargs.pop("height", None)
        kwargs.pop("activebackground", None)
        kwargs.pop("activeforeground", None)
        super().__init__(parent, **kwargs)
        self.bind("<ButtonPress-1>", self._press, add="+")
        self.bind("<ButtonRelease-1>", self._release, add="+")
        self.bind("<Leave>", self._leave, add="+")

    def configure(self, cnf=None, **kwargs):
        if cnf:
            kwargs.update(cnf)
        if "command" in kwargs:
            self.command = kwargs.pop("command")
        if "activebackground" in kwargs:
            self.activebackground = kwargs.pop("activebackground")
        if "activeforeground" in kwargs:
            self.activeforeground = kwargs.pop("activeforeground")
        if "state" in kwargs:
            self._state = kwargs.pop("state")
        if "bg" in kwargs:
            self._normal_bg = kwargs["bg"]
        elif "background" in kwargs:
            self._normal_bg = kwargs["background"]
        if "fg" in kwargs:
            self._normal_fg = kwargs["fg"]
        elif "foreground" in kwargs:
            self._normal_fg = kwargs["foreground"]
        return super().configure(**kwargs)

    config = configure

    def cget(self, key):
        if key == "state":
            return self._state
        if key == "command":
            return self.command
        if key == "activebackground":
            return self.activebackground
        if key == "activeforeground":
            return self.activeforeground
        return super().cget(key)

    def invoke(self):
        if self._state != "disabled" and self.command is not None:
            return self.command()
        return None

    def _press(self, _event):
        if self._state == "disabled":
            return
        self.focus_set()
        super().configure(bg=self.activebackground, fg=self.activeforeground)

    def _release(self, event):
        if self._state == "disabled":
            return
        super().configure(bg=self._normal_bg, fg=self._normal_fg)
        if 0 <= event.x < self.winfo_width() and 0 <= event.y < self.winfo_height():
            self.invoke()

    def _leave(self, _event):
        if self._state != "disabled":
            super().configure(bg=self._normal_bg, fg=self._normal_fg)
