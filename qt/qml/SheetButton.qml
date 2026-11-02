import QtQuick

// A row in a sheet's menu.
Rectangle {
    id: root
    property alias text: label.text
    signal clicked()
    width: parent ? parent.width : 0
    height: 48
    radius: 8
    color: area.pressed ? "#2e2e36" : "transparent"
    Text {
        id: label
        anchors.left: parent.left
        anchors.leftMargin: 8
        anchors.verticalCenter: parent.verticalCenter
        color: "#fafafa"
        font.pixelSize: 16
    }
    MouseArea {
        id: area
        anchors.fill: parent
        onClicked: root.clicked()
    }
}
