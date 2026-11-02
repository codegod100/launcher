import QtQuick

// A panel that rises from the bottom over a dimmed screen (long-press menus, the
// widget picker). Its children go in a column; tapping outside closes it.
Item {
    id: root
    property bool open: false
    property string title: ""
    property real bottomInset: 0
    property real maxWidth: 560
    default property alias content: column.data
    // Where the panel's top edge is, for hiding native widgets under it.
    readonly property real edge: panel.y
    signal closeRequested()
    anchors.fill: parent
    visible: open || panel.y < height
    enabled: open

    Rectangle {
        anchors.fill: parent
        color: "black"
        opacity: root.open ? .45 : 0
        Behavior on opacity { NumberAnimation { duration: 150 } }
        MouseArea { anchors.fill: parent; onClicked: root.closeRequested() }
    }
    Rectangle {
        id: panel
        width: Math.min(root.width, root.maxWidth)
        anchors.horizontalCenter: parent.horizontalCenter
        height: Math.min(root.height * .7, column.implicitHeight + 36 + root.bottomInset + 16)
        y: root.open ? root.height - height : root.height
        Behavior on y { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
        radius: 20
        color: "#18181b"
        MouseArea { anchors.fill: parent } // keep taps on the sheet off the overlay
        // square off the bottom corners
        Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 20; color: parent.color }
        Rectangle {
            width: 36; height: 5; radius: 3
            anchors.horizontalCenter: parent.horizontalCenter
            y: 12
            color: "#3f3f46"
        }
        Flickable {
            id: flick
            anchors.fill: parent
            anchors.topMargin: 28
            anchors.leftMargin: 16
            anchors.rightMargin: 16
            anchors.bottomMargin: root.bottomInset + 16
            contentHeight: column.implicitHeight
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            Column {
                id: column
                width: flick.width
                Text {
                    visible: root.title.length > 0
                    text: root.title
                    color: "#fafafa"
                    font.pixelSize: 16
                    font.weight: Font.DemiBold
                    leftPadding: 8
                    bottomPadding: 8
                    width: parent.width
                    elide: Text.ElideRight
                }
            }
        }
    }
}
