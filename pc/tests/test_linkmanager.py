import os
import tempfile
import time
import unittest

from faze_pc import link
from faze_pc.linkmanager import LinkManager
from tests.phone_sim import PhoneSim


def tmp():
    return tempfile.mkdtemp(prefix="faze-test-")


def wait(cond, secs=6):
    end = time.time() + secs
    while time.time() < end:
        if cond():
            return True
        time.sleep(0.05)
    return False


class ManagerTest(unittest.TestCase):
    def setUp(self):
        self.phone = PhoneSim("Pixel")
        self.devs = link.Devices(os.path.join(tmp(), "d.json"))
        self.m = LinkManager(self.devs, interval=0.2, keepalive=0.3, discover=lambda **k: [], gateway=lambda: None)
        self.m._connect = lambda a, s, pc, port=link.PORT, timeout=8: link.connect("127.0.0.1", s, pc, self.phone.port, timeout)
        self.m._pair = lambda a, code, pc, name, port=link.PORT, timeout=8: link.pair("127.0.0.1", code, pc, name, self.phone.port, timeout)

    def tearDown(self):
        self.m.stop()
        self.phone.close()

    def test_pairs_connects_and_answers_requests(self):
        phone = self.m.pair("127.0.0.1", self.phone.open_pairing())
        self.assertEqual(phone["name"], "Pixel")
        self.m.start()
        self.assertTrue(wait(lambda: self.m.list()[0]["state"] == "connected"))
        self.assertEqual(self.m.request(phone["id"], {"t": "ping"})["t"], "pong")

    def test_it_reconnects_after_the_connection_drops(self):
        phone = self.m.pair("127.0.0.1", self.phone.open_pairing())
        self.m.start()
        self.assertTrue(wait(lambda: self.m.list()[0]["state"] == "connected"))
        first = self.m.sessions[phone["id"]]
        for s in list(self.phone.sessions):
            s.close()
        self.assertTrue(wait(lambda: self.m.sessions.get(phone["id"]) not in (None, first), 8))
        self.assertEqual(self.m.list()[0]["state"], "connected")

    def test_an_unreachable_phone_shows_offline_with_a_reason(self):
        phone = self.m.pair("127.0.0.1", self.phone.open_pairing())
        self.phone.close()
        self.m.start()
        self.assertTrue(wait(lambda: self.m.list()[0]["error"] != ""))
        self.assertEqual(self.m.list()[0]["state"], "offline")
        with self.assertRaises(link.LinkError):
            self.m.request(phone["id"], {"t": "ping"})

    def test_forget_disconnects_and_removes(self):
        phone = self.m.pair("127.0.0.1", self.phone.open_pairing())
        self.m.start()
        self.assertTrue(wait(lambda: self.m.list()[0]["state"] == "connected"))
        self.assertTrue(self.m.forget(phone["id"]))
        self.assertEqual(self.m.list(), [])


if __name__ == "__main__":
    unittest.main()
