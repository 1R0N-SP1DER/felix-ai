"""Keeps the PC connected to its paired phone: finds it (saved address, the default gateway for USB or Bluetooth tethering, then a
broadcast on the network), reconnects when it drops, and lets the rest of the daemon send it requests."""
import threading
import time

from . import link


class LinkManager:
    def __init__(self, devices, connect=link.connect, discover=link.discover, gateway=link.gateway, interval=5.0, keepalive=10.0, pair_fn=link.pair):
        self.devices = devices
        self._connect = connect
        self._discover = discover
        self._gateway = gateway
        self._pair = pair_fn
        self.interval = interval
        self.keepalive = keepalive
        self.sessions = {}   # phone id -> Session
        self.errors = {}     # phone id -> last problem, shown in the Devices list
        self.locks = {}      # phone id -> lock: one request at a time per phone
        self._stop = threading.Event()
        self._wake = threading.Event()
        self._thread = None

    def start(self):
        if self._thread is None:
            self._thread = threading.Thread(target=self._loop, daemon=True, name="link")
            self._thread.start()

    def stop(self):
        self._stop.set()
        self._wake.set()
        for s in list(self.sessions.values()):
            s.close()

    def _lock(self, pid):
        return self.locks.setdefault(pid, threading.Lock())

    # --- what the window shows
    def list(self):
        out = []
        for pid, p in self.devices.phones().items():
            out.append({"id": pid, "name": p["name"], "address": p["address"], "state": "connected" if pid in self.sessions else "offline", "error": self.errors.get(pid, "")})
        return out

    def pair(self, address, code, port=link.PORT):
        phone = self._pair(address, code, self.devices.pc_id, self.devices.pc_name, port)
        self.devices.add(phone, address, port)
        self.errors.pop(phone["id"], None)
        self._wake.set()
        return phone

    def forget(self, pid):
        s = self.sessions.pop(pid, None)
        if s:
            s.close()
        self.errors.pop(pid, None)
        return self.devices.forget(pid)

    def find(self):
        targets = ["255.255.255.255"]
        gw = self._gateway()
        if gw:
            targets.append(gw)
        return self._discover(targets=tuple(targets))

    # --- talking to a connected phone
    def request(self, pid, msg, timeout=15):
        """Sends one message and returns the reply. Raises LinkError if the phone is not connected or does not answer."""
        sess = self.sessions.get(pid)
        if not sess:
            raise link.LinkError("the phone is not connected")
        with self._lock(pid):
            try:
                sess.sock.settimeout(timeout)
                sess.send(msg)
                return sess.recv()
            except Exception as e:
                self._drop(pid, "lost the connection: %s" % e)
                raise link.LinkError("lost the connection to the phone")

    def _drop(self, pid, why):
        s = self.sessions.pop(pid, None)
        if s:
            s.close()
        self.errors[pid] = why

    # --- the background loop
    def _addresses(self, pid, p):
        yield p["address"], p["port"]
        gw = self._gateway()
        if gw and gw != p["address"]:
            yield gw, link.PORT
        try:
            for f in self._discover(timeout=1.0):
                if f["id"] == pid:
                    yield f["address"], f["port"]
        except Exception:
            pass

    def _try_connect(self, pid, p):
        last = "not found on the network"
        for addr, port in self._addresses(pid, p):
            try:
                sess, ready = self._connect(addr, p["secret"], self.devices.pc_id, port)
            except Exception as e:
                last = "%s: %s" % (addr, e)
                continue
            self.sessions[pid] = sess
            self.errors.pop(pid, None)
            self.devices.remember_address(pid, addr, port)
            return
        self.errors[pid] = last

    def _loop(self):
        last_ping = {}
        while not self._stop.is_set():
            for pid, p in self.devices.phones().items():
                if self._stop.is_set():
                    return
                if pid not in self.sessions:
                    self._try_connect(pid, p)
                    last_ping[pid] = time.time()
                elif time.time() - last_ping.get(pid, 0) >= self.keepalive:
                    last_ping[pid] = time.time()
                    try:
                        self.request(pid, {"t": "ping"}, timeout=5)
                    except link.LinkError:
                        pass  # request() already marked it offline; the next round reconnects
            self._wake.wait(self.interval)
            self._wake.clear()
