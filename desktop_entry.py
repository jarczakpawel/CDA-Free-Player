import multiprocessing
import sys


def _run():
    if "--self-test" in sys.argv:
        from cda_free_player.selftest import run
        return run()
    from cda_free_player.main import main
    main()
    return 0


if __name__ == "__main__":
    multiprocessing.freeze_support()
    raise SystemExit(_run())
