import os
import unittest

from faze_pc.clip import ClipSync, MAX
from faze_pc.config import Config
from faze_pc.linkmanager import LinkManager
from tests.test_tasks import tmp


class Board:
    """A fake clipboard behind fake commands."""
    def __init__(self, text=None):
        self.text = text
        self.cmds = []

    def run(self, argv, **kw):
        self.cmds.append(argv[0])
        if "getClipboardContents" in argv or argv[0] == "wl-paste":
            return (0, self.text) if self.text is not None else (1, "")
        if "setClipboardContents" in argv:
            self.text = argv[-1]
            return 0, ""
        return 1, ""


class ClipTest(unittest.TestCase):
    def make(self, text=None, tools=("qdbus6",)):
        self.board = Board(text)
        self.sent = []
        cfg = Config(os.path.join(tmp(), "c.json"))
        return ClipSync(cfg, lambda m: self.sent.append(m) or 1, which=lambda n: "/bin/" + n if n in tools else None, runner=self.board.run)

    def test_what_was_there_at_start_is_not_sent(self):
        c = self.make("old")
        self.assertFalse(c.poll())
        self.assertEqual(self.sent, [])

    def test_a_new_copy_goes_to_the_phone_once(self):
        c = self.make("old")
        c.poll()
        self.board.text = "new text"
        self.assertTrue(c.poll())
        self.assertFalse(c.poll())
        self.assertEqual(self.sent, [{"t": "clip", "text": "new text"}])

    def test_a_copy_from_the_phone_is_set_and_not_echoed_back(self):
        c = self.make("old")
        c.poll()
        c.receive({"t": "clip", "text": "from phone"})
        self.assertEqual(self.board.text, "from phone")
        c.poll()
        self.assertEqual(self.sent, [])

    def test_off_means_nothing_moves(self):
        c = self.make("old")
        c.config.data["clip_sync"] = False
        self.board.text = "x"
        self.assertFalse(c.poll())
        c.receive({"t": "clip", "text": "y"})
        self.assertEqual(self.board.text, "x")

    def test_too_big_or_empty_is_ignored(self):
        c = self.make("old")
        c.poll()
        self.board.text = "a" * (MAX + 1)
        self.assertFalse(c.poll())
        c.receive({"t": "clip", "text": ""})
        c.receive({"t": "clip", "text": 5})
        self.assertEqual(self.board.text, "a" * (MAX + 1))

    def test_falls_back_to_wl_clipboard(self):
        c = self.make("old", tools=("wl-paste", "wl-copy"))
        self.assertEqual(c.read(), "old")

    def test_no_tool_means_no_crash(self):
        c = self.make("old", tools=())
        self.assertIsNone(c.read())
        self.assertFalse(c.write("x"))
        self.assertFalse(c.poll())

    def test_notify_reaches_every_connected_phone(self):
        got = []

        class S:
            def __init__(self, n): self.n = n
            def send(self, m): got.append((self.n, m))

        m = LinkManager(devices=None)
        m.sessions = {"a": S("a"), "b": S("b")}
        self.assertEqual(m.notify({"t": "clip", "text": "x"}), 2)
        self.assertEqual(len(got), 2)


if __name__ == "__main__":
    unittest.main()


class ClipOfflineTest(ClipTest):
    def test_with_no_phone_linked_the_clipboard_is_never_read(self):
        c = self.make("old")
        online = [False]
        c.connected = lambda: online[0]
        calls = []
        orig = c.run
        c.run = lambda argv, *a, **k: calls.append(argv) or orig(argv, *a, **k)
        self.assertFalse(c.poll())
        self.board.text = "copied while offline"
        self.assertFalse(c.poll())
        c.receive({"t": "clip", "text": "from phone"})
        self.assertEqual(calls, [])
        self.assertEqual(self.board.text, "copied while offline")
        online[0] = True
        self.assertFalse(c.poll())  # first look after linking is only a baseline: the offline copy is not sent
        self.assertEqual(self.sent, [])
        self.board.text = "after linking"
        self.assertTrue(c.poll())
