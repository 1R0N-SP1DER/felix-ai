import os
import unittest

from faze_pc.config import Config
from faze_pc.remote import RemoteControl, MAX_JPEG
from tests.test_tasks import tmp


class Rig:
    def __init__(self, tools, big=False, fail=False):
        self.tools, self.calls, self.big, self.fail = tools, [], big, fail

    def which(self, n):
        return "/bin/" + n if n in self.tools else None

    def run(self, argv, timeout=10):
        self.calls.append(argv)
        if os.path.basename(argv[0]) in ("spectacle", "grim"):
            open(argv[-1], "wb").write(b"J" * (MAX_JPEG + 1 if self.big else 100))
            return 0, b"", ""
        if os.path.basename(argv[0]) in ("magick", "convert"):
            open(argv[-1], "wb").write(b"small")
            return 0, b"", ""
        return (1, b"", "boom") if self.fail else (0, b"", "")


class RemoteTest(unittest.TestCase):
    def make(self, tools, on=True, **kw):
        cfg = Config(os.path.join(tmp(), "c.json"))
        cfg.data["allow_remote"] = on
        self.rig = Rig(tools, **kw)
        return RemoteControl(cfg, which=self.rig.which, runner=self.rig.run)

    def test_ignores_other_messages(self):
        self.assertIsNone(self.make(["ydotool"]).handle({"t": "sync_list"}))

    def test_off_by_default_says_where_to_turn_it_on(self):
        r = self.make(["ydotool"], on=False).handle({"t": "pc_input", "kind": "click"})
        self.assertEqual(r["t"], "error")
        self.assertIn("Let my phone see and control", r["message"])
        self.assertEqual(self.rig.calls, [])

    def test_move_click_scroll_key_text_with_ydotool(self):
        r = self.make(["ydotool"])
        for m in ({"kind": "move", "dx": 5.7, "dy": -3}, {"kind": "click", "button": "right"}, {"kind": "click", "double": True}, {"kind": "scroll", "dy": 3}, {"kind": "key", "key": "Enter"}, {"kind": "text", "text": "hi"}):
            self.assertEqual(r.handle(dict(m, t="pc_input"))["t"], "pc_input_ok")
        self.assertEqual(self.rig.calls[0], ["ydotool", "mousemove", "-x", "5", "-y", "-3"])
        self.assertEqual(self.rig.calls[1], ["ydotool", "click", "0xC1"])
        self.assertEqual(self.rig.calls[2], ["ydotool", "click", "0xC0", "0xC0"])
        self.assertEqual(self.rig.calls[4], ["ydotool", "key", "28:1", "28:0"])
        self.assertEqual(self.rig.calls[5], ["ydotool", "type", "--", "hi"])

    def test_xdotool_fallback(self):
        r = self.make(["xdotool"])
        r.handle({"t": "pc_input", "kind": "move", "dx": 4, "dy": 9})
        r.handle({"t": "pc_input", "kind": "scroll", "dy": -2})
        self.assertEqual(self.rig.calls[0], ["xdotool", "mousemove_relative", "--", "4", "9"])
        self.assertEqual(self.rig.calls[1], ["xdotool", "click", "--repeat", "2", "5"])

    def test_no_input_tool_and_bad_input_are_plain_errors(self):
        self.assertIn("ydotool", self.make([]).handle({"t": "pc_input", "kind": "click"})["message"])
        r = self.make(["ydotool"])
        self.assertEqual(r.handle({"t": "pc_input", "kind": "key", "key": "rm -rf"})["t"], "error")
        self.assertEqual(r.handle({"t": "pc_input", "kind": "click", "button": "x"})["t"], "error")
        self.assertEqual(r.handle({"t": "pc_input", "kind": "nope"})["t"], "error")
        self.assertEqual(self.rig.calls, [])

    def test_tool_failure_hints_at_the_daemon(self):
        r = self.make(["ydotool"], fail=True).handle({"t": "pc_input", "kind": "click"})
        self.assertIn("ydotoold", r["message"])

    def test_movement_is_clamped(self):
        r = self.make(["ydotool"])
        r.handle({"t": "pc_input", "kind": "move", "dx": 99999, "dy": -99999})
        self.assertEqual(self.rig.calls[0][-4:], ["-x", "3000", "-y", "-3000"])

    def test_screen_is_scaled_when_imagemagick_is_there(self):
        r = self.make(["spectacle", "magick"]).handle({"t": "pc_screen_get", "w": 800})
        self.assertEqual(r["t"], "pc_screen_ok")
        import base64
        self.assertEqual(base64.b64decode(r["jpeg"]), b"small")

    def test_a_huge_unscaled_screen_is_refused(self):
        r = self.make(["spectacle"], big=True).handle({"t": "pc_screen_get"})
        self.assertIn("imagemagick", r["message"])

    def test_no_screenshot_tool(self):
        self.assertIn("screenshot", self.make([]).handle({"t": "pc_screen_get"})["message"])


if __name__ == "__main__":
    unittest.main()
