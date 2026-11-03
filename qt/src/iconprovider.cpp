#include "iconprovider.h"

#include <QColor>
#include <QPainter>
#include <QUrl>

#include <cmath>

#ifdef Q_OS_ANDROID
#include <QJniEnvironment>
#include <QJniObject>
#endif

QImage IconProvider::requestImage(const QString &id, QSize *size, const QSize &requested) {
    QString key = id;
    const int q = key.lastIndexOf(QLatin1Char('?'));
    if (q >= 0) key.truncate(q);
    key = QUrl::fromPercentEncoding(key.toUtf8());
    const int px = requested.width() > 0 ? requested.width() : 144;
    QImage img;
#ifdef Q_OS_ANDROID
    // Premultiplied RGBA, side x side, straight from an Android bitmap.
    QJniObject pixels = QJniObject::callStaticObjectMethod(
        "dev/ultra/launcher/HomeActivity", "icon", "(Ljava/lang/String;I)[B",
        QJniObject::fromString(key).object<jstring>(), jint(px));
    if (pixels.isValid()) {
        QJniEnvironment env;
        auto arr = pixels.object<jbyteArray>();
        const jsize n = env->GetArrayLength(arr);
        const int side = int(std::lround(std::sqrt(n / 4.0)));
        if (side > 0 && jsize(side) * side * 4 == n) {
            img = QImage(side, side, QImage::Format_RGBA8888_Premultiplied);
            env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte *>(img.bits()));
        }
    }
#else
    // A disc in a colour picked from the key, with the name's first letter.
    img = QImage(px, px, QImage::Format_ARGB32_Premultiplied);
    img.fill(Qt::transparent);
    QPainter p(&img);
    p.setRenderHint(QPainter::Antialiasing);
    const QColor c = QColor::fromHsv(int(qHash(key) % 360), 160, 220);
    const bool widget = key.startsWith(QLatin1String("w:"));
    p.setBrush(c);
    p.setPen(Qt::NoPen);
    if (widget) p.drawRoundedRect(QRectF(0, 0, px, px), px / 4.0, px / 4.0);
    else p.drawEllipse(QRectF(0, 0, px, px));
    QString name = key.mid(2).section(QLatin1Char('/'), 0, 0).section(QLatin1Char('.'), -1);
    p.setPen(Qt::white);
    QFont f;
    f.setPixelSize(px * 5 / 12);
    f.setBold(true);
    p.setFont(f);
    p.drawText(QRectF(0, 0, px, px), Qt::AlignCenter, name.left(1).toUpper());
#endif
    if (size) *size = img.size();
    return img;
}
