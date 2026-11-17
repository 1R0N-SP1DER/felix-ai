"""D-Bus listener for the KWin edge script. Run by the daemon as `/usr/bin/python3 -m faze_pc.kwin_edge` (system Python, because it needs PyGObject).
It owns org.faze.Edge on the session bus; the script in kwin/edge.qml calls Hit(x, y) when the pointer is pushed against the left edge, and each call
becomes one line `hit X Y` on standard output. It ends when its input closes. Exit 2: PyGObject is missing."""
import sys
import threading

XML = """<node><interface name="org.faze.Edge"><method name="Hit"><arg type="i" name="x" direction="in"/><arg type="i" name="y" direction="in"/></method></interface></node>"""


def hit_line(x, y):
    return "hit %d %d" % (x, y)


def main():
    try:
        import gi
        gi.require_version("Gio", "2.0")
        from gi.repository import Gio, GLib
    except (ImportError, ValueError):
        sys.stderr.write("python3-gi is missing. Install it: sudo apt install python3-gi\n")
        return 2
    loop = GLib.MainLoop()

    def on_call(conn, sender, path, iface, method, params, invocation):
        x, y = params.unpack()
        print(hit_line(x, y), flush=True)
        invocation.return_value(None)

    def on_bus(conn, name):
        conn.register_object("/org/faze/Edge", Gio.DBusNodeInfo.new_for_xml(XML).interfaces[0], on_call, None, None)

    def on_acquired(conn, name):
        print("ready", flush=True)

    def on_lost(conn, name):
        sys.stderr.write("Could not take the name org.faze.Edge on the session bus.\n")
        loop.quit()

    Gio.bus_own_name(Gio.BusType.SESSION, "org.faze.Edge", Gio.BusNameOwnerFlags.REPLACE, on_bus, on_acquired, on_lost)
    threading.Thread(target=lambda: (sys.stdin.read(), loop.quit()), daemon=True).start()
    loop.run()
    return 0


if __name__ == "__main__":
    sys.exit(main())
