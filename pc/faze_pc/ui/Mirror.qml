import QtQuick
import QtQuick.Window

Window {
    width: 360; height: 780
    visible: true
    title: "Phone"
    color: "#04060B"
    flags: Qt.Window | Qt.WindowStaysOnTopHint
    PhoneView { anchors.fill: parent }
    onClosing: mirror.stop()
}
