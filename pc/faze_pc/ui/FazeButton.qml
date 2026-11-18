import QtQuick
import QtQuick.Controls

// A small dark button; when it is checkable, the checked one is filled with the accent.
Button {
    id: b
    implicitHeight: 34
    leftPadding: 16; rightPadding: 16
    contentItem: Text { text: b.text; color: b.checked ? "#04101C" : "#E9EEFB"; font.pixelSize: 13; font.bold: b.checked; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
    background: Rectangle { radius: 17; color: b.checked ? "#5CC8FF" : (b.hovered ? "#14FFFFFF" : "#08FFFFFF"); border.width: 1; border.color: b.checked ? "#5CC8FF" : "#2182AFFF" }
}
