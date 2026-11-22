import QtQuick
import QtQuick.Layouts

// The phone's home screen: the morphing core, a line under it (sometimes a greeting) and three starter chips that fill the message box.
Item {
    id: root
    property string user: "there"
    property string ai: "Faze"
    property string mode: "idle"
    property string model: "auto"
    property string seed: ""         // changes with each chat, so the line changes too
    signal pick(string text)

    readonly property var anyLines: ["Coffee and {ai} time?", "What's on your mind, {user}?", "Back at it, {user}?", "{user}! What are we building?", "Ready when you are.", "Hey {user}. What's the plan?", "Let's make something, {user}.", "Talk to me, {user}.", "Got a minute, {user}?", "Fire away."]
    readonly property var morning: ["Good morning, {user}.", "Morning, {user}. Coffee first?", "Fresh day, {user}. What comes first?"]
    readonly property var afternoon: ["Good afternoon, {user}.", "Afternoon, {user}. What are we tackling?", "Mid-day check-in, {user}?"]
    readonly property var evening: ["Good evening, {user}.", "Winding down or ramping up, {user}?", "Evening, {user}. What are we up to?"]
    readonly property var night: ["Still up, {user}?", "Burning the midnight oil, {user}?", "Late-night ideas, {user}?"]
    readonly property var starters: [["Search the web", "search the web for "], ["Plan a project", "Help me plan "], ["Explain something", "Explain how "]]

    // Same rule as the phone: the shared lines plus the ones for the time of day.
    function greeting() {
        var h = new Date().getHours()
        var slot = (h >= 5 && h <= 11) ? morning : (h >= 12 && h <= 16) ? afternoon : (h >= 17 && h <= 21) ? evening : night
        var pool = anyLines.concat(slot)
        var line = pool[Math.min(pool.length - 1, Math.floor(Math.random() * pool.length))]
        return line.replace("{user}", user === "" ? "there" : user).replace("{ai}", ai === "" ? "Faze" : ai)
    }

    property string line: greeting()
    onSeedChanged: line = greeting()

    ColumnLayout {
        anchors.centerIn: parent
        width: Math.min(parent.width - 40, 560)
        spacing: 14

        Core {
            Layout.alignment: Qt.AlignHCenter
            Layout.preferredWidth: Math.max(150, Math.min(root.height * 0.42, 340))
            Layout.preferredHeight: Layout.preferredWidth
            mode: root.mode
            model: root.model
        }
        Text {
            Layout.alignment: Qt.AlignHCenter
            text: "What are we building?"
            color: "#E9EEFB"
            font.pixelSize: 26
            font.bold: true
        }
        Text {
            Layout.alignment: Qt.AlignHCenter
            Layout.fillWidth: true
            horizontalAlignment: Text.AlignHCenter
            wrapMode: Text.Wrap
            text: root.line
            color: "#A5B1CA"
            font.pixelSize: 15
        }
        Flow {
            Layout.alignment: Qt.AlignHCenter
            Layout.fillWidth: true
            spacing: 8
            Repeater {
                model: root.starters
                Rectangle {
                    radius: 16
                    color: chipHover.hovered ? "#1482AFFF" : "#08FFFFFF"
                    border.width: 1
                    border.color: "#4282AFFF"
                    width: label.implicitWidth + 28
                    height: 34
                    Text { id: label; anchors.centerIn: parent; text: modelData[0]; color: "#A5B1CA"; font.pixelSize: 13 }
                    HoverHandler { id: chipHover; cursorShape: Qt.PointingHandCursor }
                    TapHandler { onTapped: root.pick(modelData[1]) }
                }
            }
        }
    }
}
