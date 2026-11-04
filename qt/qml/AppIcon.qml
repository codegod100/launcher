import QtQuick

// An app's or widget's icon from HomeActivity (image://icon/KEY), or a blank disc
// until it arrives or if it can't be drawn.
Item {
    id: root
    property string key: ""        // "a:COMPONENT" or "w:PROVIDER"
    property int generation: 0     // bumped when the app list changes, so icons reload
    property real size: 40
    width: size
    height: size

    Rectangle {
        anchors.fill: parent
        radius: width / 2
        color: Qt.rgba(1, 1, 1, .18)
        visible: img.status !== Image.Ready
    }
    Image {
        id: img
        anchors.fill: parent
        asynchronous: true
        fillMode: Image.PreserveAspectFit
        smooth: true
        mipmap: true
        sourceSize.width: Math.ceil(root.size * Screen.devicePixelRatio)
        sourceSize.height: Math.ceil(root.size * Screen.devicePixelRatio)
        onStatusChanged: if (status === Image.Ready || status === Image.Error) console.info("ultra.icon shown", root.key, status === Image.Ready)
        source: root.key ? "image://icon/" + encodeURIComponent(root.key) + "?" + root.generation : ""
    }
}
