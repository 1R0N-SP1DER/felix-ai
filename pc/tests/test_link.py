import json
import os
import socket
import tempfile
import time
import unittest

from faze_pc import link
from tests.phone_sim import PhoneSim


def tmp():
    return tempfile.mkdtemp(prefix="faze-test-")


class CryptoTest(unittest.TestCase):
    def test_known_answers_that_the_phone_side_must_match(self):
        # These exact values are also asserted in the phone's tests (LinkCryptoTest.kt): both sides must agree byte for byte.
        k = link.code_key("ABCDEFGHJK")
        self.assertEqual(k.hex(), KNOWN["code_key"])
        nc, np = bytes(range(16)), bytes(range(16, 32))
        self.assertEqual(link.proof(k, "phone", nc, np).hex(), KNOWN["proof"])
        secret = bytes(range(32))
        sk = link.session_key(secret, nc, np)
        self.assertEqual(sk.hex(), KNOWN["session_key"])
        ct = link.seal_once(sk, b"PCPC" + (0).to_bytes(8, "big"), b'{"t":"ping"}')
        self.assertEqual(ct.hex(), KNOWN["ciphertext"])
        self.assertEqual(link.open_once(sk, b"PCPC" + (0).to_bytes(8, "big"), ct), b'{"t":"ping"}')

    def test_codes_are_readable_and_cleaned(self):
        c = link.new_code()
        self.assertEqual(len(c), link.CODE_LENGTH)
        self.assertEqual(link.clean_code("abcd-efgh jk"), "ABCDEFGHJK")
        self.assertEqual(link.code_key("abcd efgh jk"), link.code_key("ABCDEFGHJK"))

    def test_tampering_is_caught(self):
        k = bytes(32)
        ct = bytearray(link.seal_once(k, bytes(12), b"hello"))
        ct[0] ^= 1
        with self.assertRaises(link.LinkError):
            link.open_once(k, bytes(12), bytes(ct))


KNOWN = {
    "code_key": "cb354a5bdfd3a916b67b89034b39edf74628a27d425968d2c9e0f137d958ca31",
    "proof": "7f16c6f5b2a48f92ba77f61da7ac82238a77f3b6480202c03f66fe0dcddaa809",
    "session_key": "ae101d852a97e906dba070b0077e542a2038470cc985050d2281ea6b82088818",
    "ciphertext": "ec24b3bb72f4cfa9a913844bf1925ce9734e08d2017900180bb8b8d7",
}


class LinkTest(unittest.TestCase):
    def setUp(self):
        self.phone = PhoneSim()
        self.devs = link.Devices(os.path.join(tmp(), "d.json"))

    def tearDown(self):
        self.phone.close()

    def pair(self, code=None):
        code = code or self.phone.open_pairing()
        p = link.pair("127.0.0.1", code, self.devs.pc_id, "My PC", self.phone.port)
        self.devs.add(p, "127.0.0.1", self.phone.port)
        return p

    def test_pair_then_connect_and_talk(self):
        p = self.pair()
        self.assertEqual(p["id"], self.phone.id)
        self.assertEqual(oct(os.stat(self.devs.path).st_mode & 0o777), "0o600")
        d = self.devs.phones()[p["id"]]
        sess, ready = link.connect("127.0.0.1", d["secret"], self.devs.pc_id, self.phone.port)
        self.assertEqual(ready["name"], "Test phone")
        sess.send({"t": "ping"})
        self.assertEqual(sess.recv()["t"], "pong")
        sess.send({"t": "ping"})
        self.assertEqual(sess.recv()["name"], "Test phone")
        sess.close()

    def test_a_wrong_code_is_refused_and_pairs_nothing(self):
        self.phone.open_pairing()
        with self.assertRaises(link.LinkError):
            link.pair("127.0.0.1", "AAAAAAAAAA", self.devs.pc_id, "My PC", self.phone.port)
        self.assertEqual(self.phone.paired, {})

    def test_pairing_must_be_open_and_closes_after_use_or_five_tries(self):
        with self.assertRaises(link.LinkError):
            link.pair("127.0.0.1", "AAAAAAAAAA", self.devs.pc_id, "x", self.phone.port)
        self.pair()
        with self.assertRaises(link.LinkError):  # the code was used up
            self.pair(code="AAAAAAAAAA")
        self.phone.open_pairing()
        for _ in range(5):
            with self.assertRaises(link.LinkError):
                link.pair("127.0.0.1", "BBBBBBBBBB", self.devs.pc_id, "x", self.phone.port)
        with self.assertRaises(link.LinkError):
            link.pair("127.0.0.1", self.phone.code, self.devs.pc_id, "x", self.phone.port)

    def test_an_unknown_pc_or_wrong_secret_cannot_connect(self):
        p = self.pair()
        d = self.devs.phones()[p["id"]]
        with self.assertRaises(link.LinkError):
            link.connect("127.0.0.1", d["secret"], "unknownpc", self.phone.port)
        with self.assertRaises(link.LinkError):
            link.connect("127.0.0.1", "00" * 32, self.devs.pc_id, self.phone.port)

    def test_replayed_or_reordered_messages_are_refused(self):
        p = self.pair()
        d = self.devs.phones()[p["id"]]
        sess, _ = link.connect("127.0.0.1", d["secret"], self.devs.pc_id, self.phone.port)
        sess.send({"t": "ping"})
        sess.recv()
        sess.sent -= 1  # reuse the counter of an earlier message
        sess.send({"t": "ping"})
        sess.sock.settimeout(1.5)
        with self.assertRaises((link.LinkError, socket.timeout, OSError)):
            sess.recv()  # the phone drops the connection: it refuses the replayed counter
        sess.close()

    def test_forgetting_a_phone(self):
        p = self.pair()
        self.assertTrue(self.devs.forget(p["id"]))
        self.assertFalse(self.devs.forget(p["id"]))
        self.assertEqual(link.Devices(self.devs.path).phones(), {})
        self.assertEqual(link.Devices(self.devs.path).pc_id, self.devs.pc_id)  # the PC keeps its identity


class DiscoveryTest(unittest.TestCase):
    def test_a_phone_that_answers_the_broadcast_is_found(self):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]

        def answer():
            data, addr = s.recvfrom(100)
            if data == b"FAZE?":
                s.sendto(json.dumps({"t": "faze", "id": "abc", "name": "Pixel", "port": 8765}).encode(), addr)
                s.sendto(b"junk", addr)

        import threading
        threading.Thread(target=answer, daemon=True).start()
        found = link.discover(timeout=1.0, port=port, targets=("127.0.0.1",))
        s.close()
        self.assertEqual(found, [{"address": "127.0.0.1", "id": "abc", "name": "Pixel", "port": 8765}])


if __name__ == "__main__":
    unittest.main()
