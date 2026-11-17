import os
import tempfile
import unittest

from faze_pc.config import Config
from faze_pc.notes import PhoneNotes


class Board:
    def __init__(self):
        self.cmds = []
        self.next = 41

    def run(self, argv, **kw):
        self.cmds.append(argv)
        if argv[0] == "notify-send":
            if "-r" in argv:
                return 0, argv[argv.index("-r") + 1] + "\n"
            self.next += 1
            return 0, "%d\n" % self.next
        return 0, ""


class NotesTest(unittest.TestCase):
    def make(self, tools=("notify-send", "gdbus"), **cfg):
        self.board = Board()
        self.logs = []
        c = Config(os.path.join(tempfile.mkdtemp(), "c.json"))
        c.data.update(cfg)
        return PhoneNotes(c, which=lambda n: "/bin/" + n if n in tools else None, runner=self.board.run, log=self.logs.append)

    def note(self, **kw):
        return dict({"t": "note", "id": "k1", "app": "Chat", "title": "Ada", "text": "hello"}, **kw)

    def test_a_notice_is_shown_and_never_answered(self):
        n = self.make()
        self.assertIsNone(n.handle(self.note()))
        argv = self.board.cmds[0]
        self.assertEqual(argv[0], "notify-send")
        self.assertEqual(argv[-2:], ["Chat · Ada", "hello"])

    def test_the_same_notification_replaces_itself(self):
        n = self.make()
        n.handle(self.note())
        n.handle(self.note(text="hello again"))
        second = self.board.cmds[1]
        self.assertIn("-r", second)
        self.assertEqual(second[second.index("-r") + 1], "42")

    def test_clearing_on_the_phone_closes_it_here(self):
        n = self.make()
        n.handle(self.note())
        n.handle({"t": "note_gone", "id": "k1"})
        self.assertEqual(self.board.cmds[-1][0], "gdbus")
        self.assertEqual(self.board.cmds[-1][-1], "42")
        n.handle({"t": "note_gone", "id": "k1"})  # already gone: nothing more
        self.assertEqual(sum(1 for c in self.board.cmds if c[0] == "gdbus"), 1)

    def test_off_means_nothing_is_shown(self):
        n = self.make(show_phone_notes=False)
        n.handle(self.note())
        self.assertEqual(self.board.cmds, [])

    def test_a_missing_notify_send_is_said_once(self):
        n = self.make(tools=())
        n.handle(self.note())
        n.handle(self.note(id="k2"))
        self.assertEqual(len(self.logs), 1)
        self.assertIn("libnotify-bin", self.logs[0])

    def test_junk_and_long_text_are_handled(self):
        n = self.make()
        n.handle({"t": "note"})
        n.handle({"t": "note", "id": 5})
        self.assertEqual(self.board.cmds, [])
        n.handle(self.note(text="x" * 5000, title=""))
        self.assertEqual(len(self.board.cmds[0][-1]), 500)
        self.assertEqual(self.board.cmds[0][-2], "Chat")


if __name__ == "__main__":
    unittest.main()
