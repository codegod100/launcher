import QtQuick

// An app: its icon over its name.
Item {
    id: root
    property string key: ""
    property string label: ""
    property int generation: 0
    property real iconSize: 40
    property real fontSize: 11
    property real spacing: 4
    property bool checkable: false // the drawer's pick mode: a check circle in the corner
    property bool checked: false
    property bool pressed: false

    Rectangle {
        anchors.fill: parent
        radius: 12
        color: Qt.rgba(1, 1, 1, .12)
        visible: root.pressed
    }
    Column {
        anchors.centerIn: parent
        width: parent.width
        spacing: root.spacing
        AppIcon {
            anchors.horizontalCenter: parent.horizontalCenter
            key: root.key
            generation: root.generation
            size: root.iconSize
        }
        Text {
            width: parent.width - 4
            anchors.horizontalCenter: parent.horizontalCenter
            text: root.label
            color: "white"
            font.pixelSize: root.fontSize
            horizontalAlignment: Text.AlignHCenter
            elide: Text.ElideRight
            style: Text.Raised
            styleColor: Qt.rgba(0, 0, 0, .55)
        }
    }
    Rectangle {
        visible: root.checkable
        width: 20; height: 20; radius: 10
        x: parent.width / 2 + root.iconSize / 2 - 12
        y: 2
        color: root.checked ? "white" : Qt.rgba(0, 0, 0, .35)
        border.color: "white"
        border.width: 2
        Text {
            anchors.centerIn: parent
            visible: root.checked
            text: "✓"
            color: "#111"
            font.pixelSize: 12
            font.bold: true
        }
    }
}
