import threading
import tkinter as tk
from tkinter import messagebox
import webbrowser

from .config import APP_NAME, AUTHOR, REPOSITORY_URL, BG, PANEL, PANEL2, TEXT, MUTED, ACCENT, SELECTED
from .version import VERSION
from .updater import check_latest, open_update


class SettingsWindow:
    def __init__(self, root, db, on_data_changed=None):
        self.root = root
        self.db = db
        self.on_data_changed = on_data_changed
        self.update_info = None

        self.win = tk.Toplevel(root)
        self.win.title(f"{APP_NAME} — Ustawienia")
        self.win.geometry("560x520")
        self.win.minsize(520, 470)
        self.win.configure(bg=BG)
        self.win.transient(root)
        self.win.grab_set()

        top = tk.Frame(self.win, bg=PANEL, padx=18, pady=16)
        top.pack(fill="x")
        tk.Label(top, text="Ustawienia", bg=PANEL, fg=TEXT, font=("Sans", 18, "bold")).pack(anchor="w")
        tk.Label(top, text=f"{APP_NAME}  {VERSION}", bg=PANEL, fg=MUTED, font=("Sans", 10)).pack(anchor="w", pady=(4,0))

        body = tk.Frame(self.win, bg=BG, padx=18, pady=14)
        body.pack(fill="both", expand=True)

        self._section(body, "Dane lokalne")
        self._danger_row(body, "Wyczyść historię oglądania", "Usuwa całą listę Ostatnio oglądane i zapisany postęp.", self.db.clear_history)
        self._danger_row(body, "Wyczyść ulubione", "Usuwa wszystkie zapisane ulubione filmy.", self.db.clear_favorites)

        self._section(body, "Aktualizacja")
        self.update_status = tk.Label(body, text="Sprawdzanie aktualizacji…", bg=BG, fg=MUTED, anchor="w", justify="left")
        self.update_status.pack(fill="x", pady=(2,6))
        self.update_button = self._button(body, "Pobierz aktualizację", self._download_update)
        self.update_button.pack(anchor="w")
        self.update_button.configure(state="disabled")

        footer = tk.Frame(body, bg=BG)
        footer.pack(fill="x", side="bottom", pady=(18,0))
        tk.Label(footer, text=f"Autor: {AUTHOR}", bg=BG, fg=MUTED).pack(anchor="w")
        link = tk.Label(footer, text=REPOSITORY_URL, bg=BG, fg=ACCENT, cursor="hand2")
        link.pack(anchor="w", pady=(4,0))
        link.bind("<Button-1>", lambda e: webbrowser.open(REPOSITORY_URL))

        threading.Thread(target=self._check_update_worker, daemon=True).start()

    def _section(self, parent, text):
        tk.Label(parent, text=text, bg=BG, fg=TEXT, font=("Sans", 12, "bold")).pack(anchor="w", pady=(10,6))

    def _button(self, parent, text, command):
        return tk.Button(parent, text=text, command=command, bg=PANEL2, fg=TEXT,
                         activebackground=SELECTED, activeforeground=TEXT, relief="flat", bd=0,
                         highlightthickness=2, highlightbackground=PANEL2, highlightcolor=ACCENT,
                         padx=12, pady=7, font=("Sans", 10, "bold"))

    def _danger_row(self, parent, title, subtitle, action):
        frame = tk.Frame(parent, bg=PANEL, padx=12, pady=10)
        frame.pack(fill="x", pady=4)
        left = tk.Frame(frame, bg=PANEL)
        left.pack(side="left", fill="x", expand=True)
        tk.Label(left, text=title, bg=PANEL, fg=TEXT, font=("Sans", 10, "bold"), anchor="w").pack(fill="x")
        tk.Label(left, text=subtitle, bg=PANEL, fg=MUTED, font=("Sans", 8), anchor="w").pack(fill="x", pady=(3,0))
        self._button(frame, "Wyczyść", lambda: self._confirm_del(title, action)).pack(side="right", padx=(10,0))

    def _confirm_del(self, title, action):
        dialog = tk.Toplevel(self.win)
        dialog.title(title)
        dialog.geometry("430x190")
        dialog.configure(bg=BG)
        dialog.transient(self.win)
        dialog.grab_set()
        tk.Label(dialog, text="Aby potwierdzić, wpisz DEL", bg=BG, fg=TEXT, font=("Sans", 11, "bold")).pack(pady=(22,8))
        entry = tk.Entry(dialog, bg=PANEL2, fg=TEXT, insertbackground=TEXT, justify="center", font=("Sans", 14, "bold"))
        entry.pack(fill="x", padx=45, ipady=6)
        row = tk.Frame(dialog, bg=BG)
        row.pack(pady=16)

        def execute():
            if entry.get().strip() != "DEL":
                messagebox.showerror("Nieprawidłowe potwierdzenie", "Wpisz dokładnie DEL.", parent=dialog)
                return
            action()
            dialog.destroy()
            if self.on_data_changed:
                self.on_data_changed()
            messagebox.showinfo("Gotowe", "Dane zostały usunięte.", parent=self.win)

        self._button(row, "Anuluj", dialog.destroy).pack(side="left", padx=4)
        self._button(row, "Potwierdź", execute).pack(side="left", padx=4)
        entry.focus_set()
        entry.bind("<Return>", lambda e: execute())

    def _check_update_worker(self):
        try:
            info = check_latest()
            self.root.after(0, lambda: self._apply_update(info))
        except Exception as exc:
            message = f"Nie udało się sprawdzić aktualizacji: {exc}"
            self.root.after(0, lambda m=message: self.update_status.configure(text=m))

    def _apply_update(self, info):
        self.update_info = info
        if info.available:
            self.update_status.configure(text=f"Dostępna wersja {info.latest}. Masz {info.current}.", fg=ACCENT)
            self.update_button.configure(state="normal")
        else:
            self.update_status.configure(text=f"Masz najnowszą wersję {info.current}.", fg=MUTED)
            self.update_button.configure(state="disabled")

    def _download_update(self):
        if self.update_info:
            open_update(self.update_info)
