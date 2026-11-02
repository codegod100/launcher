import QtQuick

// A rounded search box. Enter is "accepted"; the text is "text".
Rectangle {
    id: root
    property alias text: input.text
    property alias placeholder: hint.text
    property alias input: input
    property real fontSize: 16
    signal accepted()
    signal escaped()
    implicitHeight: 44
    radius: height / 2
    color: Qt.rgba(1, 1, 1, .1)
    border.color: Qt.rgba(1, 1, 1, .15)
    border.width: 1

    function focusNow() { input.forceActiveFocus(); Qt.inputMethod.show() }

    TextInput {
        id: input
        anchors.left: parent.left
        anchors.right: clear.left
        anchors.leftMargin: 20
        anchors.rightMargin: 8
        anchors.verticalCenter: parent.verticalCenter
        color: "white"
        selectionColor: Qt.rgba(1, 1, 1, .35)
        font.pixelSize: root.fontSize
        clip: true
        inputMethodHints: Qt.ImhNoPredictiveText | Qt.ImhNoAutoUppercase
        onAccepted: root.accepted()
        Keys.onEscapePressed: root.escaped()
        Text {
            id: hint
            anchors.fill: parent
            verticalAlignment: Text.AlignVCenter
            color: Qt.rgba(1, 1, 1, .6)
            font.pixelSize: root.fontSize
            visible: input.text.length === 0 && !input.preeditText
        }
    }
    Text {
        id: clear
        anchors.right: parent.right
        anchors.rightMargin: 14
        anchors.verticalCenter: parent.verticalCenter
        width: visible ? 20 : 0
        visible: input.text.length > 0
        text: "✕"
        color: Qt.rgba(1, 1, 1, .7)
        font.pixelSize: 14
        horizontalAlignment: Text.AlignHCenter
        MouseArea {
            anchors.fill: parent
            anchors.margins: -10
            onClicked: { input.text = ""; root.escaped() }
        }
    }
}
