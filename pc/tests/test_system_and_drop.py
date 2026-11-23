import os
import signal
import unittest

from faze_pc.config import Config
from faze_pc.engine import Engine
from faze_pc.modelcopy import DropFolder
from faze_pc.system import SystemInfo
from tests.test_tasks import tmp


def proc(root, pid, name, ticks, pages):
    d = os.path.join(root, str(pid))
    os.makedirs(d)
    with open(os.path.join(d, "stat"), "w") as f:
        f.write("%d (%s) S 1 1 1 0 -1 0 0 0 0 0 %d 0 0 0 20 0 1 0 1 1 1 0\n" % (pid, name, ticks))
    with open(os.path.join(d, "statm"), "w") as f:
        f.write("100 %d 0 0 0 0 0\n" % pages)


class SystemTest(unittest.TestCase):
    def setUp(self):
        self.root = tmp()
        cfg = Config()
        cfg.dir = tmp()
        cfg.data["allow_remote"] = True
        self.cfg = cfg
        self.killed = []
        self.now = [0.0]
        self.sys = SystemInfo(cfg, proc=self.root, sys=tmp(), kill=lambda pid, sig: self.killed.append((pid, sig)), clock=lambda: self.now[0], me=999)

    def test_busiest_first_with_cpu_between_two_looks(self):
        proc(self.root, 10, "idle thing", 0, 50)
        proc(self.root, 11, "busy thing", 0, 10)
        self.sys.procs()
        self.now[0] = 1.0
        with open(os.path.join(self.root, "11", "stat"), "w") as f:
            f.write("11 (busy thing) S 1 1 1 0 -1 0 0 0 0 0 50 0 0 0 20 0 1 0 1 1 1 0\n")
        got = self.sys.procs()
        self.assertEqual("busy thing", got[0]["name"])
        self.assertGreater(got[0]["cpu"], 0)

    def test_ending_a_program_and_refusals(self):
        self.assertEqual("ok", self.sys.handle({"t": "pc_kill", "pid": 4321})["t"])
        self.assertEqual((4321, signal.SIGTERM), self.killed[0])
        self.sys.handle({"t": "pc_kill", "pid": 4321, "force": True})
        self.assertEqual(signal.SIGKILL, self.killed[1][1])
        for bad in (1, 999, "x", None):
            self.assertEqual("error", self.sys.handle({"t": "pc_kill", "pid": bad})["t"])
        self.assertEqual(2, len(self.killed))

    def test_off_unless_the_remote_switch_is_on(self):
        self.cfg.data["allow_remote"] = False
        self.assertEqual("error", self.sys.handle({"t": "pc_procs"})["t"])

    def test_devices_listed(self):
        sysd = tmp()
        d = os.path.join(sysd, "bus", "usb", "devices", "1-1")
        os.makedirs(d)
        open(os.path.join(d, "product"), "w").write("Wireless Receiver\n")
        open(os.path.join(d, "manufacturer"), "w").write("Logitech\n")
        s = SystemInfo(self.cfg, proc=self.root, sys=sysd)
        self.assertIn({"kind": "usb", "name": "Logitech Wireless Receiver"}, s.devices())


class DropTest(unittest.TestCase):
    def make(self, ok=True):
        self.folder, self.dest = tmp(), tmp()
        cfg = Config()
        cfg.dir = tmp()
        self.said, self.ran = [], []

        def runner(argv):
            self.ran.append(argv)
            return (0, "") if ok else (1, "boom")
        self.t = [1000.0]
        return DropFolder(cfg, lambda *a: self.said.append(a), lambda: set(), folder=self.folder, dest=self.dest, which=lambda n: "/bin/" + n, runner=runner, clock=lambda: self.t[0])

    def drop(self, name, size=200 * 1024 * 1024):
        p = os.path.join(self.folder, name)
        with open(p, "wb") as f:
            f.truncate(size)
        os.utime(p, (0, 0))
        return p

    def test_a_settled_model_is_set_up_and_removed(self):
        d = self.make()
        p = self.drop("Qwen3.5-4B-Q4_K_M.gguf")
        self.assertEqual([], d.scan())  # first look: could still be copying
        self.assertEqual(["qwen3.5:4b-q4_k_m"], d.scan())
        self.assertFalse(os.path.exists(p))
        self.assertEqual([], os.listdir(self.dest))
        self.assertEqual("ollama", self.ran[0][0])

    def test_failure_gives_the_file_back(self):
        d = self.make(ok=False)
        p = self.drop("LFM2.5-1.2B.gguf")
        d.scan()
        self.assertEqual([], d.scan())
        self.assertTrue(os.path.exists(p))
        self.assertFalse(self.said[-1][3])

    def test_small_files_and_other_types_are_left_alone(self):
        d = self.make()
        self.drop("ggml-vocab-x.gguf", 1000)
        self.drop("notes.txt", 10)
        d.scan()
        self.assertEqual([], d.scan())
        self.assertEqual([], self.ran)


class LocalModelTest(unittest.TestCase):
    def test_a_default_nobody_pulled_falls_back_to_an_installed_model(self):
        cfg = Config()
        cfg.dir = tmp()
        from faze_pc.store import Store
        e = Engine(cfg, Store(tmp()), local=lambda: ["lfm2.5:1.2b", "qwen3:4b"])
        self.assertEqual("lfm2.5:1.2b", e.local_model("lfm2.5:latest"))
        self.assertEqual("qwen3:4b", e.local_model("qwen2.5:1.5b"))
        self.assertEqual("qwen3:4b", e.local_model("qwen3:4b"))
        self.assertEqual("x", Engine(cfg, Store(tmp()), local=lambda: []).local_model("x"))
