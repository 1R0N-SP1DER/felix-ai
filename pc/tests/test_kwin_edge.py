import io
import os
import time
import unittest

from faze_pc.edge import KWinEdge
from faze_pc.kwin_edge import hit_line


class Out:
    """Yields the lines, then (hold) waits like a live helper until it is told to stop."""
    def __init__(self, lines, hold):
        self.lines, self.hold, self.done = lines.splitlines(True), hold, False

    def __iter__(self):
        for l in self.lines:
            yield l
        while self.hold and not self.done:
            time.sleep(0.02)


class Proc:
    def __init__(self, lines, hold=False):
        self.stdout = Out(lines, hold)
        self.stderr = io.StringIO("")
        self.stdin = io.StringIO()
        self.ended = False

    def terminate(self):
        self.ended = True
        self.stdout.done = True


def wait(f, secs=3):
    end = time.time() + secs
    while time.time() < end:
        if f():
            return True
        time.sleep(0.02)
    return False


class KWinEdgeTest(unittest.TestCase):
    def make(self, lines, on=True, load_id="7", notes=None, hold=False):
        self.calls = []
        self.pushes = []

        def run(argv):
            self.calls.append(argv[2:])
            if "loadDeclarativeScript" in argv[3]:
                return 0, load_id + "\n"
            return 0, ""

        k = KWinEdge(lambda: on, lambda: self.pushes.append(1), (notes.append if notes is not None else lambda m: None),
                     which=lambda n: "/bin/" + n, popen=lambda *a, **kw: Proc(lines, hold), run=run, python="/bin/sh", env={"WAYLAND_DISPLAY": "wayland-0"}, switched=lambda: self.on, poll=0.05)
        return k

    def test_the_line_format(self):
        self.assertEqual(hit_line(0, 540), "hit 0 540")

    def test_not_usable_without_wayland_or_qdbus(self):
        k = KWinEdge(lambda: True, lambda: None, which=lambda n: None, env={"WAYLAND_DISPLAY": "w"}, python="/bin/sh")
        self.assertFalse(k.start())
        k = KWinEdge(lambda: True, lambda: None, which=lambda n: "/bin/x", env={}, python="/bin/sh")
        self.assertFalse(k.start())

    def test_a_hit_pushes_once_per_report_and_only_when_wanted(self):
        self.on = True
        k = self.make("ready\nhit 0 10\nhit 0 20\n")
        k.start()
        self.assertTrue(wait(lambda: len(self.pushes) == 2))
        self.on = True
        k2 = self.make("ready\nhit 0 10\n", on=False)
        k2.start()
        time.sleep(0.3)
        self.assertEqual(self.pushes, [])

    def test_the_script_is_loaded_while_switched_on_and_unloaded_when_off(self):
        self.on = True
        k = self.make("ready\n", hold=True)
        k.start()
        self.assertTrue(wait(lambda: k.ready or any("run" in c[-1] for c in self.calls)))
        self.assertIn(["/Scripting/Script7", "org.kde.kwin.Script.run"], self.calls)
        self.on = False
        self.assertTrue(wait(lambda: not k.loaded))
        self.assertTrue(any(c[-2:] == ["org.kde.kwin.Scripting.unloadScript", "faze-edge"] for c in self.calls[-3:]))
        k.stop()

    def test_a_refusal_from_kwin_is_noted_and_not_ready(self):
        self.on = True
        notes = []
        k = self.make("ready\n", load_id="-1", notes=notes, hold=True)
        k.start()
        self.assertTrue(wait(lambda: any("would not load" in n for n in notes)))
        self.assertFalse(k.ready)
        k.stop()

    def test_a_helper_that_ends_is_noted(self):
        self.on = True
        notes = []
        k = self.make("", notes=notes)
        k.start()
        self.assertTrue(wait(lambda: any("stopped" in n for n in notes)))


if __name__ == "__main__":
    unittest.main()
