"""The Qt window. It only draws and forwards: everything real happens in the daemon."""
import os
import sys
import threading

from PySide6.QtCore import QMetaObject, QObject, Property, QTimer, QUrl, Qt, Signal, Slot
from PySide6.QtGui import QGuiApplication
from PySide6.QtNetwork import QLocalServer, QLocalSocket
from PySide6.QtQml import QQmlApplicationEngine

from .. import client as dclient

HERE = os.path.dirname(__file__)


def instance_name():
    return "faze-ui-%d" % os.getuid()


class Bridge(QObject):
    userMessage = Signal(str)
    assistantStart = Signal()
    token = Signal(str)
    assistantDone = Signal(bool)
    route = Signal(str)
    note = Signal(str)
    failed = Signal(str)
    chatsChanged = Signal("QVariantList")
    configChanged = Signal("QVariantMap")
    historyLoaded = Signal("QVariantList")
    busyChanged = Signal()
    chatChanged = Signal(str)
    approval = Signal(str, str, str)
    devicesChanged = Signal("QVariantList")
    phonesFound = Signal("QVariantList")
    pairFailed = Signal(str)
    toolUsed = Signal(str, bool)
    activityLoaded = Signal("QVariantList")

    def __init__(self, floating, demo=False):
        super().__init__()
        self._floating = floating
        self._busy = False
        self._chat = ""
        self._demo = demo
        self._client = None
        self._lock = threading.Lock()

    @Property(bool, constant=True)
    def floating(self):
        return self._floating

    @Property(str, constant=True)
    def demoPopup(self):
        return os.environ.get("FAZE_DEMO_POPUP", "")

    @Property(bool, notify=busyChanged)
    def busy(self):
        return self._busy

    def _set_busy(self, v):
        if self._busy != v:
            self._busy = v
            self.busyChanged.emit()

    def start(self):
        if self._demo:
            QTimer.singleShot(0, self._demo_fill)
            return
        self._client = dclient.Client()
        threading.Thread(target=self._read, daemon=True).start()
        self._send({"op": "state"})
        self._send({"op": "devices"})

    def _send(self, obj):
        with self._lock:
            if self._client:
                self._client.send(obj)

    def _read(self):
        for ev in self._client.events():
            t = ev.get("t")
            if t == "state":
                self.configChanged.emit(ev["config"])
                self.chatsChanged.emit(ev["chats"])
            elif t == "chat":
                self._chat = ev["id"]
                self.chatChanged.emit(ev["id"])
            elif t == "history":
                self.historyLoaded.emit(ev["messages"])
            elif t == "route":
                self.route.emit("%s · %s" % (ev["label"], ev["model"]))
            elif t == "token":
                self.token.emit(ev["v"])
            elif t == "done":
                self._set_busy(False)
                self.assistantDone.emit(bool(ev.get("stopped")))
                self._send({"op": "state"})
            elif t == "devices":
                self.devicesChanged.emit(ev["devices"])
            elif t == "found":
                self.phonesFound.emit(ev["phones"])
            elif t == "approve":
                self.approval.emit(ev["id"], ev["tool"], ev["summary"])
            elif t == "tool":
                self.toolUsed.emit(ev["summary"], bool(ev.get("ok")))
            elif t == "activity":
                self.activityLoaded.emit([{"when": r["t"], "kind": r["kind"], "text": r["text"]} for r in ev["rows"]])
            elif t == "note":
                self.note.emit(ev["message"])
            elif t == "error":
                if ev["message"].startswith(("pairing failed", "could not search")):
                    self.pairFailed.emit(ev["message"])
                else:
                    self._set_busy(False)
                    self.failed.emit(ev["message"])

    @Slot(str)
    def send(self, text):
        text = text.strip()
        if not text or self._busy:
            return
        self._set_busy(True)
        self.userMessage.emit(text)
        self.assistantStart.emit()
        self._send({"op": "chat", "chat": self._chat or None, "text": text})

    @Slot()
    def quit(self):
        QGuiApplication.quit()

    @Slot()
    def stop(self):
        self._send({"op": "stop"})

    @Slot()
    def newChat(self):
        self._chat = ""
        self.chatChanged.emit("")

    @Slot(str)
    def openChat(self, chat_id):
        self._chat = chat_id
        self.chatChanged.emit(chat_id)
        self._send({"op": "history", "chat": chat_id})

    @Slot(str, bool)
    def answer(self, request_id, ok):
        self._send({"op": "approve", "id": request_id, "ok": ok})

    @Slot()
    def refreshDevices(self):
        self._send({"op": "devices"})

    @Slot()
    def findPhones(self):
        self._send({"op": "link_find"})

    @Slot(str, str)
    def pairPhone(self, address, code):
        self._send({"op": "link_pair", "address": address, "code": code})

    @Slot(str)
    def forgetDevice(self, device_id):
        self._send({"op": "link_forget", "id": device_id})

    @Slot()
    def loadActivity(self):
        self._send({"op": "activity"})

    @Slot(str, str)
    def setSetting(self, name, value):
        self._send({"op": "set", "name": name, "value": value})

    def _demo_fill(self):
        self.configChanged.emit({"provider": "auto", "keys": {"groq": True, "gemini": False, "openrouter": False, "nvidia": False, "custom": False},
                                 "models": {}, "ollama_chat_model": "qwen2.5:1.5b", "ollama_deep_model": "qwen2.5:1.5b", "user_name": "Felix"})
        self.chatsChanged.emit([{"id": "a1", "title": "Plan for the weekend", "t": 3}, {"id": "a2", "title": "Fix this Kotlin function", "t": 2}, {"id": "a3", "title": "Voice lines", "t": 1}])
        self.historyLoaded.emit([
            {"role": "user", "content": "hey, what should I eat tonight?"},
            {"role": "assistant", "content": "Something quick and warm. Jollof with fried plantain? You haven't had it in a while."},
            {"role": "user", "content": "ha, you know me. why does this python function crash?"},
        ])
        self.route.emit("Gemini · gemini-3.1-flash-lite")
        self.toolUsed.emit("Opened Firefox", True)
        self.approval.emit("demo", "run_command", "Run: df -h")
        self.devicesChanged.emit([{"id": "p1", "name": "Pixel 8", "address": "192.168.43.1", "state": "connected", "error": ""},
                                  {"id": "p2", "name": "Old phone", "address": "192.168.1.20", "state": "offline", "error": "192.168.1.20: timed out"}])


def run_ui(floating=False):
    os.environ.setdefault("QT_QUICK_CONTROLS_STYLE", "Basic")
    # A second launch only shows or hides the window that is already open (what a keyboard shortcut wants).
    sock = QLocalSocket()
    sock.connectToServer(instance_name())
    if sock.waitForConnected(200):
        sock.write(b"toggle")
        sock.flush()
        sock.waitForBytesWritten(500)
        return 0
    app = QGuiApplication(sys.argv)
    app.setApplicationName("Faze")
    demo = bool(os.environ.get("FAZE_DEMO"))
    bridge = Bridge(floating, demo)
    engine = QQmlApplicationEngine()
    engine.rootContext().setContextProperty("bridge", bridge)
    engine.load(QUrl.fromLocalFile(os.path.join(HERE, "Main.qml")))
    if not engine.rootObjects():
        sys.stderr.write("the window could not be loaded\n")
        return 1
    root = engine.rootObjects()[0]
    server = QLocalServer()
    QLocalServer.removeServer(instance_name())
    server.listen(instance_name())

    def on_conn():
        c = server.nextPendingConnection()
        c.readyRead.connect(lambda: (c.readAll(), QMetaObject.invokeMethod(root, "toggle")))

    server.newConnection.connect(on_conn)
    bridge.start()
    shot = os.environ.get("FAZE_SCREENSHOT")
    if shot:
        def grab():
            try:
                from PySide6.QtQuick import QQuickWindow
                try:
                    img = QQuickWindow.grabWindow(root)  # the Qt Quick renderer's own picture of the window
                except Exception:
                    img = root.screen().grabWindow(root.winId()).toImage()
                ok = (not img.isNull()) and img.save(shot)
                sys.stderr.write("screenshot %s: %s\n" % (shot, "saved" if ok else "could not be saved"))
            except Exception:
                import traceback
                traceback.print_exc()
            app.quit()
        QTimer.singleShot(1500, grab)
        QTimer.singleShot(20000, app.quit)  # never hang if the picture cannot be taken
    return app.exec()
