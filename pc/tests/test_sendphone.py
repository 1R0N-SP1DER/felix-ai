import base64
import os
import tempfile
import unittest

from faze_pc.sendphone import CHUNK, PhoneSender


class SendTest(unittest.TestCase):
    def make(self, reply=None):
        self.sent, self.said = [], []
        def request(pid, msg, timeout):
            self.sent.append(msg)
            return reply(msg) if reply else {"t": "phone_put_ok"}
        return PhoneSender(request, "ph", lambda *a: self.said.append(a))

    def test_text_and_links_are_told_apart(self):
        s = self.make()
        s.send_text("hello there")
        s.send_text("https://example.com/x")
        self.assertEqual([m["kind"] for m in self.sent], ["text", "link"])
        self.assertTrue(self.said[-1][3])

    def test_empty_text_is_not_sent(self):
        s = self.make()
        s.send_text("   ")
        self.assertEqual(self.sent, [])
        self.assertFalse(self.said[-1][3])

    def test_a_file_goes_in_pieces_in_order(self):
        d = tempfile.mkdtemp()
        p = os.path.join(d, "big file.bin")
        data = os.urandom(CHUNK * 2 + 10)
        open(p, "wb").write(data)
        s = self.make()
        s.send_file("file://" + p.replace(" ", "%20"))
        self.assertEqual([m["offset"] for m in self.sent], [0, CHUNK, CHUNK * 2])
        self.assertEqual([m["eof"] for m in self.sent], [False, False, True])
        self.assertEqual(b"".join(base64.b64decode(m["data"]) for m in self.sent), data)
        self.assertEqual(self.sent[0]["name"], "big file.bin")
        self.assertTrue(self.said[-1][2] and self.said[-1][3])

    def test_empty_file_is_one_piece(self):
        p = os.path.join(tempfile.mkdtemp(), "e.txt")
        open(p, "wb").close()
        s = self.make()
        s.send_file(p)
        self.assertEqual(len(self.sent), 1)
        self.assertTrue(self.sent[0]["eof"])

    def test_the_phones_refusal_is_reported(self):
        p = os.path.join(tempfile.mkdtemp(), "a.txt")
        open(p, "wb").write(b"x")
        s = self.make(lambda m: {"t": "error", "message": "Receiving is off on the phone."})
        s.send_file(p)
        self.assertEqual(self.said[-1][0], "Receiving is off on the phone.")
        self.assertFalse(self.said[-1][3])

    def test_a_missing_file(self):
        s = self.make()
        s.send_file("/no/such/file")
        self.assertEqual(self.sent, [])
        self.assertFalse(self.said[-1][3])
