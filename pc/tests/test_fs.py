import base64
import os
import tempfile
import unittest

from faze_pc.config import Config
from faze_pc.fs import CHUNK, FsError, Shares
from faze_pc.peer import PeerRequests


class FsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = os.path.realpath(tempfile.mkdtemp(prefix="faze-test-"))
        self.docs = os.path.join(self.tmp, "Docs")
        os.makedirs(os.path.join(self.docs, "sub"))
        with open(os.path.join(self.docs, "a.txt"), "w") as f:
            f.write("hello")
        with open(os.path.join(self.docs, "sub", "b.bin"), "wb") as f:
            f.write(os.urandom(CHUNK + 100))
        self.secret = os.path.join(self.tmp, "secret.txt")
        with open(self.secret, "w") as f:
            f.write("nope")
        os.symlink(self.secret, os.path.join(self.docs, "leak.txt"))
        os.symlink(self.tmp, os.path.join(self.docs, "outdir"))
        self.cfg = Config(os.path.join(self.tmp, "c.json"))
        self.cfg.data["share_folders"] = "%s, %s/missing, " % (self.docs, self.tmp)
        self.s = Shares(self.cfg)

    def test_only_existing_listed_folders_are_roots(self):
        self.assertEqual([(r, n) for r, n, _ in self.s.roots()], [("Docs", "Docs")])

    def test_share_all_adds_a_root_that_reaches_everything(self):
        self.assertNotIn("All files", [r for r, _, _ in self.s.roots()])
        self.cfg.set("share_all", "true")
        self.assertEqual(self.s.roots()[0][:2], ("All files", "All files on this PC"))
        rel = self.secret.lstrip("/")
        import base64
        self.assertEqual(base64.b64decode(self.s.read("All files", rel, 0, 100)["data"]), b"nope")
        with self.assertRaises(FsError):
            self.s.resolve("All files", "../etc")
        self.cfg.set("share_all", "false")
        self.assertNotIn("All files", [r for r, _, _ in self.s.roots()])

    def test_lists_folders_first_and_hides_links_that_leave(self):
        names = [e["name"] for e in self.s.list("Docs", "")]
        self.assertEqual(names, ["sub", "a.txt"])
        self.assertEqual(self.s.list("Docs", "sub")[0]["size"], CHUNK + 100)

    def test_cannot_escape_the_folder(self):
        for rel in ("..", "../secret.txt", "sub/../../secret.txt", "leak.txt", "outdir/secret.txt", "/etc/passwd", "a.txt/../../x"):
            with self.assertRaises(FsError, msg=rel):
                self.s.read("Docs", rel, 0, 10)
        with self.assertRaises(FsError):
            self.s.list("Docs", "outdir")
        with self.assertRaises(FsError):
            self.s.list("Other", "")

    def test_reads_in_chunks_to_the_end(self):
        got, off = b"", 0
        while True:
            r = self.s.read("Docs", "sub/b.bin", off, CHUNK)
            part = base64.b64decode(r["data"])
            got += part
            off += len(part)
            if r["eof"]:
                break
        with open(os.path.join(self.docs, "sub", "b.bin"), "rb") as f:
            self.assertEqual(got, f.read())

    def test_over_long_requests_are_capped_and_folders_are_not_files(self):
        self.assertLessEqual(len(base64.b64decode(self.s.read("Docs", "sub/b.bin", 0, 10**9)["data"])), CHUNK)
        with self.assertRaises(FsError):
            self.s.read("Docs", "sub", 0, 10)

    def test_the_phone_protocol(self):
        logged = []
        p = PeerRequests(self.cfg, log=lambda k, t: logged.append((k, t)))
        self.assertEqual(p.handle("x", {"t": "fs_roots"})["roots"], [{"id": "Docs", "name": "Docs"}])
        self.assertEqual(p.handle("x", {"t": "fs_list", "root": "Docs", "path": ""})["t"], "fs_list_ok")
        r = p.handle("x", {"t": "fs_read", "root": "Docs", "path": "a.txt", "offset": 0, "len": 100})
        self.assertEqual((r["t"], base64.b64decode(r["data"]), r["eof"], r["size"]), ("fs_read_ok", b"hello", True, 5))
        self.assertEqual(len(logged), 1)
        self.assertEqual(p.handle("x", {"t": "fs_read", "root": "Docs", "path": "../secret.txt"})["t"], "error")
        self.assertEqual(p.handle("x", {"t": "fs_read", "root": "Docs", "path": "a.txt", "offset": "x"})["t"], "error")


if __name__ == "__main__":
    unittest.main()
