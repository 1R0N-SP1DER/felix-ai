import QtQuick
import QtQuick.Layouts

// One row: a name and a short explanation on the left, the control on the right, a thin line under it (unless it is the last one).
Item {
    id: row
    property string label: ""
    property string sub: ""
    property bool last: false
    default property alias control: slot.data
    Layout.fillWidth: true
    implicitHeight: Math.max(56, inner.implicitHeight + 24)
    RowLayout {
        id: inner
        anchors.left: parent.left; anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter
        anchors.leftMargin: 16; anchors.rightMargin: 16
        spacing: 14
        ColumnLayout {
            Layout.fillWidth: true
            spacing: 2
            Text { text: row.label; color: "#E9EEFB"; font.pixelSize: 14; Layout.fillWidth: true; wrapMode: Text.Wrap }
            Text { visible: row.sub !== ""; text: row.sub; color: "#6D7A98"; font.pixelSize: 12; Layout.fillWidth: true; wrapMode: Text.Wrap }
        }
        RowLayout { id: slot; Layout.alignment: Qt.AlignVCenter }
    }
    Rectangle { visible: !row.last; anchors.bottom: parent.bottom; anchors.left: parent.left; anchors.right: parent.right; anchors.leftMargin: 16; height: 1; color: "#14FFFFFF" }
}
