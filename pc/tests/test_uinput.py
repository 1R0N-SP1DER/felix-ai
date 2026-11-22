import os
import struct
import unittest

from faze_pc import uinput
from faze_pc.config import Config
from faze_pc.remote import RemoteControl
from tests.test_tasks import tmp
from tests.test_stream import NoUInput


class Kernel:
    """Stands in for /dev/uinput: records ioctls and what is written to each device."""
    def __init__(self, fail_open=False):
        self.fail_open, self.ioctls, self.writes, self.n = fail_open, [], {}, 0

    def opener(self, path, flags):
        if self.fail_open:
            raise PermissionError("no access")
        self.n += 1
        self.writes[self.n] = []
        return self.n

    def ioctl(self, fd, req, arg=0):
        self.ioctls.append((fd, req, arg))

    def write(self, fd, data):
        self.writes[fd].append(data)

    def events(self, fd):
        out = []
        for chunk in self.writes[fd]:
            for i in range(0, len(chunk), 24):
                _, _, t, c, v = struct.unpack("llHHi", chunk[i:i + 24])
                if t != uinput.EV_SYN:
                    out.append((t, c, v))
        return out


class UInputTest(unittest.TestCase):
    def make(self, **kw):
        self.k = Kernel(**kw)
        return uinput.UInput(opener=self.k.opener, ioctl=self.k.ioctl, writer=self.k.write, closer=lambda fd: None, sleep=lambda s: None)

    def test_two_devices_are_created_with_the_right_abilities(self):
        u = self.make()
        u.open()
        reqs = [(fd, req, arg) for fd, req, arg in self.k.ioctls]
        self.assertIn((1, uinput.UI_SET_RELBIT, uinput.REL_X), reqs)
        self.assertIn((1, uinput.UI_SET_KEYBIT, 0x110), reqs)
        self.assertIn((2, uinput.UI_SET_KEYBIT, 30), reqs)
        self.assertEqual(sum(1 for r in reqs if r[1] == uinput.UI_DEV_CREATE), 2)
        setup = [a for _, r, a in reqs if r == uinput.UI_DEV_SETUP]
        self.assertTrue(all(len(a) == 92 for a in setup))

    def test_move_click_scroll(self):
        u = self.make()
        u.open()
        u.move(5, -3); u.click("right"); u.scroll(2)
        self.assertEqual(self.k.events(1), [(2, 0, 5), (2, 1, -3), (1, 0x111, 1), (1, 0x111, 0), (2, 8, 2)])

    def test_typing_uses_shift_for_capitals_and_symbols(self):
        u = self.make()
        u.open()
        u.type_text("Hi!")
        ev = self.k.events(2)
        self.assertEqual(ev[:4], [(1, 42, 1), (1, 35, 1), (1, 35, 0), (1, 42, 0)])  # shift h
        self.assertEqual(ev[4:6], [(1, 23, 1), (1, 23, 0)])  # i
        self.assertEqual(ev[6:], [(1, 42, 1), (1, 2, 1), (1, 2, 0), (1, 42, 0)])  # shift 1 = !

    def test_named_keys_and_unknown(self):
        u = self.make()
        u.open()
        u.named_key("enter")
        self.assertEqual(self.k.events(2), [(1, 28, 1), (1, 28, 0)])
        with self.assertRaises(KeyError):
            u.named_key("nope")

    def test_combo_presses_in_order_and_releases_in_reverse(self):
        u = self.make()
        u.open()
        u.combo(["ctrl", "shift", "t"])
        self.assertEqual(self.k.events(2), [(1, 29, 1), (1, 42, 1), (1, 20, 1), (1, 20, 0), (1, 42, 0), (1, 29, 0)])
        u.hold("alt", True)
        self.assertEqual(self.k.events(2)[-1], (1, 56, 1))
        with self.assertRaises(KeyError):
            u.combo(["ctrl", "nope"])

    def test_cannot_type_what_a_us_keyboard_lacks(self):
        self.assertTrue(uinput.can_type("Hello, World! 123\n"))
        self.assertFalse(uinput.can_type("héllo"))

    def test_no_access_raises(self):
        with self.assertRaises(OSError):
            self.make(fail_open=True).open()


class RemoteDirectTest(unittest.TestCase):
    def make(self, fail=False):
        cfg = Config(os.path.join(tmp(), "c.json"))
        cfg.data["allow_remote"] = True
        self.k = Kernel(fail_open=fail)
        u = uinput.UInput(opener=self.k.opener, ioctl=self.k.ioctl, writer=self.k.write, closer=lambda fd: None, sleep=lambda s: None)
        self.calls = []
        r = RemoteControl(cfg, which=lambda n: "/bin/" + n if n == "ydotool" else None, runner=lambda argv, t=10: (self.calls.append(argv), (0, b"", ""))[1], uinput=u)
        return r

    def test_input_goes_straight_to_the_virtual_devices(self):
        r = self.make()
        self.assertEqual(r.handle({"t": "pc_input", "kind": "move", "dx": 4, "dy": 2})["t"], "pc_input_ok")
        r.handle({"t": "pc_input", "kind": "down"}); r.handle({"t": "pc_input", "kind": "up"})
        self.assertEqual(self.k.events(1), [(2, 0, 4), (2, 1, 2), (1, 0x110, 1), (1, 0x110, 0)])
        self.assertEqual(self.calls, [])

    def test_without_access_it_falls_back_to_the_other_tools_once(self):
        r = self.make(fail=True)
        self.assertEqual(r.handle({"t": "pc_input", "kind": "click"})["t"], "pc_input_ok")
        self.assertEqual(self.calls[0][:2], ["ydotool", "click"])
        self.assertTrue(r.uinput_failed)

    def test_text_outside_the_us_layout_uses_the_other_tools(self):
        r = self.make()
        r.handle({"t": "pc_input", "kind": "text", "text": "héllo"})
        self.assertEqual(self.calls[0][:2], ["ydotool", "type"])


if __name__ == "__main__":
    unittest.main()
