import os
import struct
import unittest

from faze_pc import edge

DEVICES = """I: Bus=0003 Vendor=046d Product=c077 Version=0111
N: Name="Logitech USB Optical Mouse"
H: Handlers=mouse0 event5

I: Bus=0003 Vendor=046a Product=0023 Version=0100
N: Name="HID Keyboard"
H: Handlers=sysrq kbd event3 leds

I: Bus=0006 Vendor=4641 Product=5a45 Version=0001
N: Name="Faze virtual mouse"
H: Handlers=mouse1 event9
"""


class DetectorTest(unittest.TestCase):
    def push(self, d, start, secs, dx=-60, step=0.01):
        t, fired = start, False
        while t < start + secs:
            fired = d.feed(t, dx) or fired
            t += step
        return fired

    def test_a_steady_push_fires_once(self):
        d = edge.EdgeDetector()
        self.assertTrue(self.push(d, 0, 1.5))
        self.assertFalse(self.push(d, 1.6, 0.3))  # quiet period

    def test_a_quick_flick_does_not(self):
        d = edge.EdgeDetector()
        self.assertFalse(self.push(d, 0, 0.3, dx=-300))

    def test_pulling_back_resets_it(self):
        d = edge.EdgeDetector()
        self.assertFalse(self.push(d, 0, 0.6))
        d.feed(0.61, 40)
        self.assertFalse(self.push(d, 0.62, 0.4))

    def test_a_pause_resets_it(self):
        d = edge.EdgeDetector()
        self.push(d, 0, 0.5)
        self.assertFalse(self.push(d, 1.2, 0.3))

    def test_slow_drift_is_not_a_push(self):
        d = edge.EdgeDetector()
        self.assertFalse(self.push(d, 0, 3, dx=-1, step=0.02))


class DevicesTest(unittest.TestCase):
    def test_only_real_mice(self):
        self.assertEqual(edge.mouse_devices(DEVICES), ["/dev/input/event5"])


class WatcherTest(unittest.TestCase):
    def test_raw_events_reach_the_detector(self):
        pushed = []
        now = [0.0]
        w = edge.EdgeWatcher(lambda: True, lambda: pushed.append(1), clock=lambda: now[0])
        ev = struct.pack("llHHi", 0, 0, edge.EV_REL, edge.REL_X, -70)
        for i in range(160):
            now[0] = i * 0.01
            w.feed_bytes(ev)
        self.assertEqual(pushed, [1])

    def test_other_axes_and_key_events_are_ignored(self):
        pushed = []
        w = edge.EdgeWatcher(lambda: True, lambda: pushed.append(1), clock=lambda: 0.0)
        w.feed_bytes(struct.pack("llHHi", 0, 0, edge.EV_REL, 1, -5000) + struct.pack("llHHi", 0, 0, 1, 272, 1))
        self.assertEqual(pushed, [])


if __name__ == "__main__":
    unittest.main()
