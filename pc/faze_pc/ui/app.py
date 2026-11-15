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
            elif t == "note":
                self.note.emit(ev["message"])
            elif t == "error":
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
        # The page itself saves the picture (a QML window is drawn by QML), then asks the app to quit.
        root.setProperty("shotPath", shot)
        QTimer.singleShot(1500, lambda: QMetaObject.invokeMethod(root, "shoot"))
        QTimer.singleShot(20000, app.quit)  # never hang if the picture cannot be taken
    return app.exec()
