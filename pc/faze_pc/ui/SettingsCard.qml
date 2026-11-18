import QtQuick
import QtQuick.Layouts

// A titled card of setting rows, like the phone's Settings page.
ColumnLayout {
    id: card
    property string title: ""
    default property alias rows: body.data
    Layout.fillWidth: true
    spacing: 6
    Text { text: card.title; color: "#6D7A98"; font.pixelSize: 12; font.bold: true; font.letterSpacing: 1.2; Layout.leftMargin: 6; Layout.topMargin: 12 }
    Rectangle {
        Layout.fillWidth: true
        implicitHeight: body.implicitHeight
        radius: 18
        color: "#0E1421"
        border.width: 1
        border.color: "#2182AFFF"
        ColumnLayout { id: body; anchors.left: parent.left; anchors.right: parent.right; spacing: 0 }
    }
}
