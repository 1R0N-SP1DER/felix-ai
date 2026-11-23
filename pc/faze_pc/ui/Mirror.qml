import QtQuick
import QtQuick.Window
import QtQuick.Controls
import QtQuick.Layouts

Window {
    id: win
    width: 360; height: 780
    visible: true
    title: "Phone"
    color: "#04060B"
    flags: Qt.Window | Qt.WindowStaysOnTopHint
    // What this PC's mouse and keyboard may do to the phone while the pointer is in this window.
    property bool control: true
    property string pass: "both"   // "both", "mouse" or "keyboard"
    readonly property bool mouseOk: control && pass !== "keyboard"
    readonly property bool keysOk: control && pass !== "mouse"

    Image {
        id: screenImg
        anchors.fill: parent
        fillMode: Image.PreserveAspectFit
        cache: false
        source: mirror.frameNo > 0 ? "image://phone/frame?" + mirror.frameNo : ""
    }
    Text {
        visible: mirror.frameNo === 0 || mirror.problem !== ""
        anchors.centerIn: parent
        width: parent.width - 40
        horizontalAlignment: Text.AlignHCenter
        wrapMode: Text.Wrap
        text: mirror.problem !== "" ? mirror.problem : "Waiting for the phone…"
        color: mirror.problem !== "" ? "#FF6B7A" : "#8A96B2"
        font.pixelSize: 14
    }
    MouseArea {
        id: pad
        anchors.fill: parent
        property real sx: 0; property real sy: 0; property var t0: 0
        function fx(v) { var w = screenImg.paintedWidth, off = (width - w) / 2; return w > 0 ? Math.max(0, Math.min(1, (v - off) / w)) : 0 }
        function fy(v) { var h = screenImg.paintedHeight, off = (height - h) / 2; return h > 0 ? Math.max(0, Math.min(1, (v - off) / h)) : 0 }
        enabled: win.mouseOk
        onPressed: function (m) { sx = m.x; sy = m.y; t0 = Date.now(); keys.forceActiveFocus() }
        onReleased: function (m) {
            var moved = Math.abs(m.x - sx) + Math.abs(m.y - sy)
            if (moved < 12) mirror.input(Date.now() - t0 > 500 ? "long" : "tap", fx(sx), fy(sy), 0, 0)
            else mirror.input("swipe", fx(sx), fy(sy), fx(m.x), fy(m.y))
        }
        onWheel: function (w) { mirror.input("swipe", 0.5, w.angleDelta.y > 0 ? 0.3 : 0.7, 0.5, w.angleDelta.y > 0 ? 0.7 : 0.3) }
    }
    // Keyboard: letters are typed on the phone; Enter, Backspace, Tab, arrows and Esc (back) are phone keys. Ctrl+H is Home.
    Item {
        id: keys
        focus: true
        Keys.onPressed: function (e) {
            if (!win.keysOk) return
            var map = {}
            map[Qt.Key_Return] = "enter"; map[Qt.Key_Enter] = "enter"; map[Qt.Key_Backspace] = "delete"; map[Qt.Key_Tab] = "tab"
            map[Qt.Key_Up] = "up"; map[Qt.Key_Down] = "down"; map[Qt.Key_Escape] = "back"
            if ((e.modifiers & Qt.ControlModifier) && e.key === Qt.Key_H) { mirror.key("home"); e.accepted = true; return }
            if (map[e.key] !== undefined) { mirror.key(map[e.key]); e.accepted = true; return }
            if (!(e.modifiers & (Qt.ControlModifier | Qt.AltModifier | Qt.MetaModifier)) && e.text.length > 0 && e.text.charCodeAt(0) >= 32) { mirror.text(e.text); e.accepted = true }
        }
    }
    Rectangle {
        id: bar
        anchors.top: parent.top; anchors.horizontalCenter: parent.horizontalCenter; anchors.topMargin: 8
        height: 38; width: barRow.implicitWidth + 20; radius: 19
        color: "#CC0B1220"; border.width: 1; border.color: "#552482AF"
        RowLayout {
            id: barRow
            anchors.centerIn: parent
            spacing: 8
            Text { text: "Control phone"; color: "#C8D2EA"; font.pixelSize: 12 }
            Switch { checked: win.control; onToggled: win.control = checked }
            ComboBox {
                id: modeBox
                enabled: win.control
                implicitWidth: 130; implicitHeight: 30
                model: ["Mouse + keyboard", "Mouse only", "Keyboard only"]
                currentIndex: win.pass === "both" ? 0 : (win.pass === "mouse" ? 1 : 2)
                onActivated: win.pass = ["both", "mouse", "keyboard"][currentIndex]
            }
        }
    }
    onClosing: mirror.stop()
}
