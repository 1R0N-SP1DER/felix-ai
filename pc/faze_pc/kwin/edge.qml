// KWin script: tells Faze when the mouse pointer has been pushed against the left edge of the screen.
// Loaded by Faze through KWin's scripting D-Bus interface (not installed system-wide); unloaded when Faze quits.
import QtQuick
import org.kde.kwin

Item {
    id: root
    property int still: 0
    property bool fired: false

    Timer {
        interval: 50
        repeat: true
        running: true
        onTriggered: {
            var p = Workspace.cursorPos;
            var left = Workspace.virtualScreenGeometry.x;
            if (p.x <= left) {
                root.still += 1;
                if (root.still >= 8 && !root.fired) {   // about 0.4 s against the edge
                    root.fired = true;
                    callDBus("org.faze.Edge", "/org/faze/Edge", "org.faze.Edge", "Hit", Math.round(p.x), Math.round(p.y));
                }
            } else {
                root.still = 0;
                if (p.x > left + 40) root.fired = false;   // moved away: ready for the next push
            }
        }
    }
}
