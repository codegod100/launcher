#pragma once

#include <QQuickImageProvider>

// image://icon/a:COMPONENT is an app's icon, image://icon/w:PROVIDER a widget's.
// On a phone HomeActivity draws them; on a desktop they are lettered discs.
// QML asks with the key percent-encoded and an optional "?n" suffix that only
// busts the pixmap cache after the app list changes.
class IconProvider : public QQuickImageProvider {
public:
    IconProvider() : QQuickImageProvider(QQuickImageProvider::Image) {}
    QImage requestImage(const QString &id, QSize *size, const QSize &requested) override;
};
