import os, sys
os.environ["QT_QPA_PLATFORM"] = "offscreen"
from PySide6.QtCore import QObject, Property, Slot, QUrl, QTimer
from PySide6.QtGui import QGuiApplication
from PySide6.QtQml import QQmlApplicationEngine

class M(QObject):
    @Property(int, constant=True)
    def frameNo(self): return 0
    @Property(str, constant=True)
    def problem(self): return ""
    @Slot(str, float, float, float, float)
    def input(self, *a): pass
    @Slot(str)
    def key(self, k): pass
    @Slot(str)
    def text(self, k): pass
    @Slot()
    def stop(self): pass

app = QGuiApplication(sys.argv)
e = QQmlApplicationEngine()
m = M()
e.rootContext().setContextProperty("mirror", m)
e.load(QUrl.fromLocalFile(sys.argv[1]))
def snap():
    w = e.rootObjects()[0]
    from PySide6.QtQuick import QQuickWindow
    try:
        import shiboken6
        q = shiboken6.wrapInstance(shiboken6.getCppPointer(w)[0], QQuickWindow)
        img = q.grabWindow()
    except Exception:
        img = w.screen().grabWindow(w.winId()).toImage()
    sys.stderr.write("screenshot %s: %s\n" % (sys.argv[2], "saved" if (not img.isNull()) and img.save(sys.argv[2]) else "could not be saved"))
    app.quit()
QTimer.singleShot(1500, snap)
app.exec()
