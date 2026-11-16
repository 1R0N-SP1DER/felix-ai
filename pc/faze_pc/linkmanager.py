"""Keeps the PC connected to its paired phone: finds it (saved address, the default gateway for USB or Bluetooth tethering, then a
broadcast on the network), reconnects when it drops, and lets the rest of the daemon send it requests."""
import queue
import threading
import time

from . import link


class LinkManager:
    def __init__(self, devices, connect=link.connect, discover=link.discover, gateway=link.gateway, interval=5.0, keepalive=10.0, pair_fn=link.pair, syncer=None, sync_every=30.0, on_synced=None, on_request=None):
        self.devices = devices
        self._connect = connect
        self._discover = discover
        self._gateway = gateway
        self._pair = pair_fn
        self.syncer = syncer
        self.sync_every = sync_every
        self.on_synced = on_synced
        self.synced = {}  # phone id -> {"at": time, "note": text}
        self.interval = interval
        self.keepalive = keepalive
        self.sessions = {}   # phone id -> Session
        self.errors = {}     # phone id -> last problem, shown in the Devices list
        self.send_locks = {}  # phone id -> lock: one message written at a time
        self.pending = {}     # (phone id, request number) -> queue waiting for the answer
        self.on_request = on_request  # called with (phone id, message) for what the phone asks; returns the reply
        self._lock = threading.Lock()
        self._rid = 0
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

    # --- what the window shows
    def list(self):
        out = []
        for pid, p in self.devices.phones().items():
            out.append({"id": pid, "name": p["name"], "address": p["address"], "state": "connected" if pid in self.sessions else "offline", "error": self.errors.get(pid, ""), "synced": self.synced.get(pid, {}).get("note", "")})
        return out

    def pair(self, address, code, port=link.PORT):
        phone = self._pair(address, code, self.devices.pc_id, self.devices.pc_name, port)
        self.devices.add(phone, address, port)
        self.errors.pop(phone["id"], None)
        self._wake.set()
        return phone

    def forget(self, pid):
        s = self.sessions.get(pid)
        if s:
            self._drop(pid, "forgotten", s)
        self.errors.pop(pid, None)
        return self.devices.forget(pid)

    def find(self):
        targets = ["255.255.255.255"]
        gw = self._gateway()
        if gw:
            targets.append(gw)
        return self._discover(targets=tuple(targets))

    # --- talking to a connected phone. The link is two-way: either side can ask. A message with "rid" is answered with the same number in "re".
    def request(self, pid, msg, timeout=15):
        """Asks the phone something and returns its answer. Raises LinkError if it is not connected or does not answer."""
        sess = self.sessions.get(pid)
        if not sess:
            raise link.LinkError("the phone is not connected")
        with self._lock:
            self._rid += 1
            rid = self._rid
        q = queue.Queue()
        self.pending[(pid, rid)] = q
        try:
            self._send(pid, sess, dict(msg, rid=rid))
            try:
                reply = q.get(timeout=timeout)
            except queue.Empty:
                raise link.LinkError("the phone did not answer in time")
            if reply is None:
                raise link.LinkError("lost the connection to the phone")
            return reply
        finally:
            self.pending.pop((pid, rid), None)

    def notify(self, msg):
        """Sends a message nobody waits an answer for to every connected phone. Returns how many it reached."""
        n = 0
        for pid, sess in list(self.sessions.items()):
            try:
                self._send(pid, sess, msg)
                n += 1
            except link.LinkError:
                pass
        return n

    def _send(self, pid, sess, msg):
        try:
            with self.send_locks.setdefault(pid, threading.Lock()):
                sess.send(msg)
        except Exception as e:
            self._drop(pid, "lost the connection: %s" % e, sess)
            raise link.LinkError("lost the connection to the phone")

    def _drop(self, pid, why, sess=None):
        cur = self.sessions.get(pid)
        if cur is None or (sess is not None and cur is not sess):
            return  # already dropped, or a newer connection replaced it
        self.sessions.pop(pid, None)
        cur.close()
        self.errors[pid] = why
        for (p, rid), q in list(self.pending.items()):
            if p == pid:
                q.put(None)

    def _read(self, pid, sess):
        """Reads everything the phone sends: answers go to whoever asked, and questions go to on_request."""
        while True:
            try:
                msg = sess.recv()
            except Exception as e:
                self._drop(pid, "lost the connection: %s" % e, sess)
                return
            if "re" in msg:
                q = self.pending.get((pid, msg["re"]))
                if q:
                    q.put(msg)
            else:
                threading.Thread(target=self._answer, args=(pid, sess, msg), daemon=True).start()

    def _answer(self, pid, sess, msg):
        if msg.get("t") == "ping":
            reply = {"t": "pong"}
        else:
            try:
                reply = self.on_request(pid, msg) if self.on_request else None
            except Exception as e:
                reply = {"t": "error", "message": str(e)[:200]}
        if reply is not None:
            if "rid" in msg:
                reply = dict(reply, re=msg["rid"])
            try:
                self._send(pid, sess, reply)
            except link.LinkError:
                pass

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
            threading.Thread(target=self._read, args=(pid, sess), daemon=True, name="link-read").start()
            self.errors.pop(pid, None)
            self.devices.remember_address(pid, addr, port)
            return
        self.errors[pid] = last

    def sync_now(self, pid):
        """One sync round with a connected phone. Failures are shown in the Devices list, never fatal."""
        if not self.syncer or pid not in self.sessions:
            return None
        try:
            got = self.syncer.run(pid, lambda m: self.request(pid, m, timeout=30))
        except Exception as e:
            self.synced[pid] = {"at": time.time(), "note": "sync failed: %s" % e}
            return None
        changed = got["facts_in"] or got["chats_in"]
        self.synced[pid] = {"at": time.time(), "note": "synced %s" % time.strftime("%H:%M")}
        if changed and self.on_synced:
            try:
                self.on_synced(pid, got)
            except Exception:
                pass
        return got

    def _loop(self):
        last_ping = {}
        last_sync = {}
        while not self._stop.is_set():
            for pid, p in self.devices.phones().items():
                if self._stop.is_set():
                    return
                if pid not in self.sessions:
                    self._try_connect(pid, p)
                    last_ping[pid] = time.time()
                    last_sync[pid] = 0
                elif time.time() - last_sync.get(pid, 0) >= self.sync_every:
                    last_sync[pid] = time.time()
                    self.sync_now(pid)
                elif time.time() - last_ping.get(pid, 0) >= self.keepalive:
                    last_ping[pid] = time.time()
                    try:
                        self.request(pid, {"t": "ping"}, timeout=5)
                    except link.LinkError:
                        pass  # request() already marked it offline; the next round reconnects
            self._wake.wait(self.interval)
            self._wake.clear()
