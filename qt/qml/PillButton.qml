import QtQuick

// A rounded text button: "ghost" (clear until pressed) or "primary" (filled).
Rectangle {
    id: root
    property alias text: label.text
    property bool primary: false
    property real fontSize: 14
    signal clicked()
    implicitWidth: label.implicitWidth + 28
    implicitHeight: 36
    radius: height / 2
    color: primary ? (area.pressed ? "#d4d4d8" : "white")
                   : (area.pressed ? Qt.rgba(1, 1, 1, .18) : "transparent")
    Text {
        id: label
        anchors.centerIn: parent
        color: root.primary ? "#111" : "white"
        font.pixelSize: root.fontSize
        font.weight: Font.Medium
    }
    MouseArea {
        id: area
        anchors.fill: parent
        onClicked: root.clicked()
    }
}
