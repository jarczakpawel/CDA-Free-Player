import multiprocessing
import sys
import os
import traceback


def _run():
    if "--self-test" in sys.argv:
        from cda_free_player.selftest import run
        return run()
    from cda_free_player.main import main
    main()
    return 0


if __name__ == "__main__":
    multiprocessing.freeze_support()
    from cda_free_player.config import LOG_DIR
    log = None
    if sys.stdout is None or sys.stderr is None:
        log = open(LOG_DIR / "desktop-startup.log", "w", encoding="utf-8", buffering=1)
        if sys.stdout is None: sys.stdout = log
        if sys.stderr is None: sys.stderr = log
    if sys.stdin is None:
        sys.stdin = open(os.devnull, "r")
    try:
        raise SystemExit(_run())
    except Exception:
        error = traceback.format_exc()
        (LOG_DIR / "desktop-startup-error.log").write_text(error, encoding="utf-8")
        if "--self-test" not in sys.argv:
            try:
                from tkinter import messagebox
                messagebox.showerror("CDA Free Player", "Błąd uruchamiania. Log: " + str(LOG_DIR / "desktop-startup-error.log"))
            except Exception:
                pass
        raise
