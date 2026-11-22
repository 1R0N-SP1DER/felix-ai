import QtQuick
import QtQuick.Layouts

// The right-hand panel of the desktop window, like a remote receiver: the phone's state at the top and the things it can do from here as tiles.
Rectangle {
    id: root
    property var devices: []
    property color surface: "#0E1421"
    property color accent: "#5CC8FF"
    property color text: "#E9EEFB"
    property color dim: "#A5B1CA"
    property color muted: "#6D7A98"
    property color line: "#2182AFFF"
    signal action(string name)

    color: surface
    readonly property var connected: devices.filter(function (d) { return d.state === "connected" })
    readonly property var tiles: [
        { "id": "screen", "label": "Phone screen", "sub": "see and control it", "glyph": "▭" },
        { "id": "voice", "label": "Voice chat", "sub": "talk to Faze", "glyph": "◉" },
        { "id": "live", "label": "Live", "sub": "Gemini Live", "glyph": "✦" },
        { "id": "devices", "label": "Devices", "sub": "pair, share files", "glyph": "⇄" },
        { "id": "activity", "label": "Activity", "sub": "what Faze did", "glyph": "≡" },
        { "id": "settings", "label": "Settings", "sub": "keys, models, voice", "glyph": "⚙" }
    ]

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 16
        spacing: 12

        Text { text: "PHONE"; color: root.muted; font.pixelSize: 11; font.bold: true; font.letterSpacing: 2 }

        Rectangle {
            Layout.fillWidth: true
            radius: 16
            color: "#08FFFFFF"
            border.width: 1
            border.color: root.line
            implicitHeight: info.implicitHeight + 24
            ColumnLayout {
                id: info
                anchors.fill: parent
                anchors.margins: 12
                spacing: 4
                RowLayout {
                    spacing: 8
                    Rectangle { width: 9; height: 9; radius: 5; color: root.connected.length > 0 ? "#6EE7A8" : "#F3C969"; Layout.alignment: Qt.AlignVCenter }
                    Text {
                        Layout.fillWidth: true
                        text: root.connected.length > 0 ? root.connected[0].name : (root.devices.length > 0 ? root.devices[0].name : "No phone yet")
                        color: root.text; font.pixelSize: 14; font.bold: true; elide: Text.ElideRight
                    }
                }
                Text {
                    Layout.fillWidth: true
                    wrapMode: Text.Wrap
                    text: root.connected.length > 0 ? "Connected. Clipboard syncs while you are." : (root.devices.length > 0 ? "Paired, but not connected right now." : "Open Devices and pair your phone.")
                    color: root.dim; font.pixelSize: 12
                }
            }
        }

        GridLayout {
            Layout.fillWidth: true
            columns: 2
            rowSpacing: 8
            columnSpacing: 8
            Repeater {
                model: root.tiles
                Rectangle {
                    Layout.fillWidth: true
                    Layout.preferredHeight: 84
                    radius: 16
                    color: tileHover.hovered ? "#1482AFFF" : "#08FFFFFF"
                    border.width: 1
                    border.color: root.line
                    ColumnLayout {
                        anchors.fill: parent
                        anchors.margins: 12
                        spacing: 2
                        Text { text: modelData.glyph; color: root.accent; font.pixelSize: 20 }
                        Text { text: modelData.label; color: root.text; font.pixelSize: 13; font.bold: true }
                        Text { text: modelData.sub; color: root.muted; font.pixelSize: 11; elide: Text.ElideRight; Layout.fillWidth: true }
                    }
                    HoverHandler { id: tileHover; cursorShape: Qt.PointingHandCursor }
                    TapHandler { onTapped: root.action(modelData.id) }
                }
            }
        }
        Item { Layout.fillHeight: true }
        Text {
            Layout.fillWidth: true
            wrapMode: Text.Wrap
            text: "Push the mouse against the left edge of the screen to show the phone."
            color: root.muted; font.pixelSize: 11
        }
    }
}
