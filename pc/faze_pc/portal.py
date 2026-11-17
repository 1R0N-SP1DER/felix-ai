"""The screen-cast helper. Run by the stream code as `/usr/bin/python3 -m faze_pc.portal W FPS QUALITY`, with the system Python because it needs PyGObject (python3-gi).
It asks the desktop's screen-cast portal (the same thing OBS and remote-desktop apps use) for the screen. The desktop shows an approval box the first time;
the approval is remembered in a token file so later runs are silent. It then starts gst-launch-1.0 reading the PipeWire stream and writing a continuous stream
of JPEG pictures to this program's standard output, and stays alive (the portal session ends when this process ends) until its input closes.
Exit codes: 0 fine, 2 something is missing (the message on stderr says what), 3 the user said no."""
import json
import os
import subprocess
import sys

PORTAL = "org.freedesktop.portal.Desktop"
PATH = "/org/freedesktop/portal/desktop"


def gst_args(fd, node, width, fps, quality):
    """The gst-launch command that turns the PipeWire stream into JPEG pictures on stdout."""
    return ["gst-launch-1.0", "-q", "pipewiresrc", "fd=%d" % fd, "path=%d" % node, "do-timestamp=true", "!",
            "videorate", "!", "video/x-raw,framerate=%d/1" % fps, "!",
            "videoscale", "!", "video/x-raw,width=%d" % width, "!",
            "videoconvert", "!", "jpegenc", "quality=%d" % quality, "!", "fdsink", "fd=1"]


def token_file():
    base = os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config")
    return os.path.join(base, "faze", "screencast.token")


def fail(code, message):
    sys.stderr.write(message + "\n")
    sys.exit(code)


def main(argv):
    width, fps, quality = (int(a) for a in argv[:3])
    try:
        import gi
        gi.require_version("Gio", "2.0")
        from gi.repository import Gio, GLib
    except (ImportError, ValueError):
        fail(2, "python3-gi is missing. Install it: sudo apt install python3-gi")
    import shutil
    if not shutil.which("gst-launch-1.0"):
        fail(2, "GStreamer is missing. Install it: sudo apt install gstreamer1.0-tools gstreamer1.0-pipewire gstreamer1.0-plugins-good")

    bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
    sender = bus.get_unique_name()[1:].replace(".", "_")
    counter = [0]

    def token():
        counter[0] += 1
        return "faze%d_%d" % (os.getpid(), counter[0])

    def call(method, build):
        """One portal call whose answer arrives later as a Response signal. build(handle_token) returns the call's arguments."""
        t = token()
        loop = GLib.MainLoop()
        result = {}

        def on_response(conn, snd, obj, iface, sig, params):
            result["r"] = params.unpack()
            loop.quit()

        sub = bus.signal_subscribe(PORTAL, "org.freedesktop.portal.Request", "Response", "%s/request/%s/%s" % (PATH, sender, t), None, Gio.DBusSignalFlags.NONE, on_response)
        try:
            bus.call_sync(PORTAL, PATH, "org.freedesktop.portal.ScreenCast", method, build(t), None, Gio.DBusCallFlags.NONE, -1, None)
            GLib.timeout_add_seconds(120, loop.quit)  # nobody answered the approval box
            loop.run()
        finally:
            bus.signal_unsubscribe(sub)
        if "r" not in result:
            fail(3, "No answer from the screen-share approval box.")
        return result["r"]

    saved = ""
    try:
        saved = open(token_file()).read().strip()
    except OSError:
        pass

    code, res = call("CreateSession", lambda t: GLib.Variant("(a{sv})", ({"handle_token": GLib.Variant("s", t), "session_handle_token": GLib.Variant("s", token())},)))
    if code != 0:
        fail(2, "The desktop would not start a screen-share session. Is xdg-desktop-portal-kde running?")
    session = res["session_handle"]

    def select(t):
        opts = {"handle_token": GLib.Variant("s", t), "types": GLib.Variant("u", 1), "multiple": GLib.Variant("b", False),
                "cursor_mode": GLib.Variant("u", 2), "persist_mode": GLib.Variant("u", 2)}
        if saved:
            opts["restore_token"] = GLib.Variant("s", saved)
        return GLib.Variant("(oa{sv})", (session, opts))

    code, _ = call("SelectSources", select)
    if code != 0:
        fail(2, "The desktop refused the screen source.")
    code, res = call("Start", lambda t: GLib.Variant("(osa{sv})", (session, "", {"handle_token": GLib.Variant("s", t)})))
    if code != 0:
        fail(3, "Screen sharing was not approved.")
    streams = res.get("streams") or []
    if not streams:
        fail(2, "The desktop gave no screen.")
    node = streams[0][0]
    new_token = res.get("restore_token")
    if new_token:
        try:
            os.makedirs(os.path.dirname(token_file()), exist_ok=True)
            with open(token_file(), "w") as f:
                f.write(new_token)
            os.chmod(token_file(), 0o600)
        except OSError:
            pass
    reply, fds = bus.call_with_unix_fd_list_sync(PORTAL, PATH, "org.freedesktop.portal.ScreenCast", "OpenPipeWireRemote", GLib.Variant("(oa{sv})", (session, {})), GLib.VariantType("(h)"), Gio.DBusCallFlags.NONE, -1, None, None)
    fd = fds.get(reply.unpack()[0])
    os.set_inheritable(fd, True)
    proc = subprocess.Popen(gst_args(fd, node, width, fps, quality), pass_fds=(fd,), stdin=subprocess.DEVNULL)
    try:
        sys.stdin.buffer.read()  # blocks until the daemon closes our input, which is how it says stop
    finally:
        proc.terminate()
        try:
            proc.wait(3)
        except subprocess.TimeoutExpired:
            proc.kill()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
