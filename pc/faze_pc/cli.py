"""The `faze` command: opens the window (or the floating one), or talks from the terminal. Bind it to a shortcut in KDE."""
import argparse
import sys

from . import client


def ask(text, chat=None):
    c = client.Client()
    try:
        c.send({"op": "chat", "chat": chat, "text": text})
        for ev in c.events():
            if ev["t"] == "token":
                sys.stdout.write(ev["v"])
                sys.stdout.flush()
            elif ev["t"] == "route":
                sys.stderr.write("[%s %s]\n" % (ev["label"], ev["model"]))
            elif ev["t"] == "error":
                sys.stderr.write("\n" + ev["message"] + "\n")
                return 1
            elif ev["t"] == "done":
                sys.stdout.write("\n")
                return 0
    finally:
        c.close()
    return 1


def main(argv=None):
    p = argparse.ArgumentParser(prog="faze", description="Faze on your desktop")
    p.add_argument("--float", action="store_true", help="the small floating window (run it again to hide it)")
    p.add_argument("--daemon", action="store_true", help="run only the background daemon, in front")
    p.add_argument("--set", nargs=2, metavar=("NAME", "VALUE"), help="change a setting, e.g. keys.groq gsk_...")
    p.add_argument("text", nargs="*", help="ask from the terminal instead of opening a window")
    a = p.parse_args(argv)
    if a.daemon:
        from .daemon import main as run
        return run()
    if not client.start_daemon():
        sys.stderr.write("could not start the Faze daemon\n")
        return 1
    if a.set:
        c = client.Client()
        ev = c.request({"op": "set", "name": a.set[0], "value": a.set[1]})
        c.close()
        return 0 if ev and ev["t"] == "state" else 1
    if a.text:
        return ask(" ".join(a.text))
    from .ui.app import run_ui
    return run_ui(floating=a.float)


if __name__ == "__main__":
    sys.exit(main())
