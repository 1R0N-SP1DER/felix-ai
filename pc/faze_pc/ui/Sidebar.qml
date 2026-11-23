import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// The phone's chats drawer, as a permanent glass column: the brand with its little core, New chat, search, chats grouped by age, and Settings / Devices / profile at the bottom.
Rectangle {
    id: root
    property var chats: []
    property string current: ""
    property int connected: 0
    property string user: "You"
    signal newChat()
    signal openChat(string id)
    signal openSettings()
    signal openDevices()

    readonly property color text: "#E9EEFB"
    readonly property color dim: "#A5B1CA"
    readonly property color muted: "#6D7A98"
    readonly property color accent: "#5CC8FF"
    readonly property color accent2: "#3D7BFF"
    readonly property color line: "#2182AFFF"
    readonly property color line2: "#4282AFFF"
    readonly property color glowSoft: "#245CC8FF"

    color: "#A80E1422"

    function seconds(t) { var n = typeof t === "number" ? t : Date.parse(t) / 1000; return n > 1e12 ? n / 1000 : n }
    function flat() {
        var q = search.text.toLowerCase()
        var d = new Date(); d.setHours(0, 0, 0, 0)
        var today = d.getTime() / 1000
        var buckets = [["Today", today, []], ["Yesterday", today - 86400, []], ["Previous 7 days", today - 7 * 86400, []], ["Older", -1, []]]
        for (var i = 0; i < chats.length; i++) {
            var c = chats[i]
            if (q !== "" && c.title.toLowerCase().indexOf(q) < 0) continue
            var s = seconds(c.t)
            for (var b = 0; b < buckets.length; b++) if (s >= buckets[b][1]) { buckets[b][2].push(c); break }
        }
        var out = []
        for (var k = 0; k < buckets.length; k++) {
            if (buckets[k][2].length === 0) continue
            out.push({ "kind": "h", "title": buckets[k][0], "id": "", "ttl": "" })
            for (var j = 0; j < buckets[k][2].length; j++) out.push({ "kind": "c", "title": "", "id": buckets[k][2][j].id, "ttl": buckets[k][2][j].title })
        }
        return out
    }

    Rectangle { anchors.right: parent.right; width: 1; height: parent.height; color: root.line }

    ColumnLayout {
        anchors.fill: parent
        anchors.leftMargin: 12; anchors.rightMargin: 12; anchors.topMargin: 16; anchors.bottomMargin: 12
        spacing: 12

        RowLayout {
            Layout.fillWidth: true
            spacing: 0
            Core { Layout.preferredWidth: 52; Layout.preferredHeight: 52; Layout.leftMargin: -8; mode: "idle"; model: "auto" }
            Text { text: "FAZE"; color: root.text; font.pixelSize: 14; font.bold: true; font.letterSpacing: 4.4; Layout.leftMargin: -2 }
            Item { Layout.fillWidth: true }
        }

        Rectangle {
            id: newBtn
            Layout.fillWidth: true
            implicitHeight: 44
            radius: 14
            border.width: 1
            border.color: newHover.hovered ? root.accent : root.line2
            gradient: Gradient { orientation: Gradient.Horizontal; GradientStop { position: 0; color: root.glowSoft } GradientStop { position: 1; color: "transparent" } }
            Row {
                anchors.verticalCenter: parent.verticalCenter; anchors.left: parent.left; anchors.leftMargin: 14; spacing: 8
                Text { text: "+"; color: root.accent; font.pixelSize: 20; anchors.verticalCenter: parent.verticalCenter }
                Text { text: "New chat"; color: root.text; font.pixelSize: 15; font.weight: Font.DemiBold; anchors.verticalCenter: parent.verticalCenter }
            }
            HoverHandler { id: newHover; cursorShape: Qt.PointingHandCursor }
            TapHandler { onTapped: root.newChat() }
        }

        Rectangle {
            Layout.fillWidth: true
            implicitHeight: 38
            radius: 14
            color: search.activeFocus ? "#0DFFFFFF" : "#08FFFFFF"
            border.width: 1
            border.color: search.activeFocus ? root.accent : root.line
            TextInput {
                id: search
                anchors.fill: parent; anchors.leftMargin: 12; anchors.rightMargin: 12
                verticalAlignment: TextInput.AlignVCenter
                color: root.text; font.pixelSize: 14; clip: true
                selectionColor: root.glowSoft
                Text { visible: search.text === "" && !search.activeFocus; anchors.verticalCenter: parent.verticalCenter; text: "Search chats"; color: root.muted; font.pixelSize: 14 }
            }
        }

        ListView {
            id: list
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            spacing: 2
            model: root.flat()
            delegate: Item {
                width: ListView.view.width
                height: modelData.kind === "h" ? 30 : 38
                Text {
                    visible: modelData.kind === "h"
                    anchors.left: parent.left; anchors.leftMargin: 8; anchors.bottom: parent.bottom; anchors.bottomMargin: 4
                    text: modelData.title.toUpperCase(); color: root.muted; font.pixelSize: 11; font.weight: Font.DemiBold; font.letterSpacing: 1.5
                }
                Rectangle {
                    visible: modelData.kind === "c"
                    anchors.fill: parent
                    radius: 12
                    color: modelData.id === root.current ? root.glowSoft : (rowHover.hovered ? "#0AFFFFFF" : "transparent")
                    Rectangle { visible: modelData.id === root.current; width: 2; height: parent.height - 12; anchors.verticalCenter: parent.verticalCenter; radius: 1; color: root.accent }
                    Text {
                        anchors.fill: parent; anchors.leftMargin: 12; anchors.rightMargin: 8
                        verticalAlignment: Text.AlignVCenter
                        text: modelData.ttl; elide: Text.ElideRight; font.pixelSize: 14
                        color: modelData.id === root.current ? root.text : root.dim
                    }
                    HoverHandler { id: rowHover; cursorShape: Qt.PointingHandCursor }
                    TapHandler { onTapped: root.openChat(modelData.id) }
                }
            }
            Text { visible: root.chats.length === 0; anchors.horizontalCenter: parent.horizontalCenter; y: 12; text: "No chats yet"; color: root.muted; font.pixelSize: 13 }
        }

        Rectangle { Layout.fillWidth: true; height: 1; color: root.line }
        RowLayout {
            Layout.fillWidth: true
            spacing: 4
            Repeater {
                model: [{ "t": "Settings", "a": "settings" }, { "t": root.connected > 0 ? "Devices · " + root.connected : "Devices", "a": "devices" }]
                Rectangle {
                    implicitWidth: lbl.implicitWidth + 24; implicitHeight: 36; radius: 14
                    color: linkHover.hovered ? root.glowSoft : "transparent"
                    Text { id: lbl; anchors.centerIn: parent; text: modelData.t; color: linkHover.hovered ? root.text : root.dim; font.pixelSize: 14 }
                    HoverHandler { id: linkHover; cursorShape: Qt.PointingHandCursor }
                    TapHandler { onTapped: modelData.a === "settings" ? root.openSettings() : root.openDevices() }
                }
            }
            Item { Layout.fillWidth: true }
            Rectangle {
                implicitHeight: 38; implicitWidth: 38; radius: 19
                gradient: Gradient { orientation: Gradient.Horizontal; GradientStop { position: 0; color: root.accent } GradientStop { position: 1; color: root.accent2 } }
                Text { anchors.centerIn: parent; text: root.user.length > 0 ? root.user.charAt(0).toUpperCase() : "F"; color: "#04101C"; font.pixelSize: 14; font.bold: true }
            }
        }
    }
}
