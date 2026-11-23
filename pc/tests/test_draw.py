import json
import unittest

from faze_pc.draw import DrawRelay, clean


class FakeProc:
    def __init__(self):
        self.lines = []
        self.stdin = self

    def write(self, b): self.lines.append(json.loads(b))
    def flush(self): pass
    def close(self): pass
    def poll(self): return None


class DrawTest(unittest.TestCase):
    def test_clean(self):
        self.assertIsNone(clean({"op": "rm"}))
        self.assertEqual(clean({"op": "start", "color": "#ff0000", "x": 2, "y": -1}), {"op": "start", "color": "#ff0000", "x": 1.0, "y": 0.0})
        self.assertEqual(clean({"op": "start", "color": "red;rm", "x": 0.5, "y": 0.5})["color"], "#5CC8FF")
        self.assertEqual(clean({"op": "end"}), {"op": "end"})

    def test_relay_starts_on_first_stroke_only(self):
        procs = []
        def spawn(*a, **k):
            procs.append(FakeProc()); return procs[-1]
        r = DrawRelay(spawn)
        self.assertEqual(r.handle({"op": "clear"})["t"], "ok")
        self.assertEqual(procs, [])
        r.handle({"op": "start", "color": "00ff00", "x": 0.1, "y": 0.2})
        r.handle({"op": "move", "x": 0.3, "y": 0.4})
        self.assertEqual(len(procs), 1)
        self.assertEqual([l["op"] for l in procs[0].lines], ["start", "move"])
        self.assertEqual(r.handle({"op": "bogus"})["t"], "error")
