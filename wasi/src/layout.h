#pragma once

#include <QRectF>
#include <QSet>
#include <QString>
#include <QVector>

namespace Layout {

inline constexpr qreal kCellH = 92.0;
inline constexpr qreal kColGap = 6.0;
inline constexpr qreal kRowGap = 12.0;

struct HomeSlot {
    QString component; // non-empty for app slots
    int widgetId = -1; // >= 0 for Android AppWidget slots
    QString provider;  // widget provider component
    int x = -1;
    int y = -1;
    int w = 1;
    int h = 1;

    bool isWidget() const { return widgetId >= 0 || !provider.isEmpty(); }
};

struct CellPos {
    int col = 0;
    int row = 0;
};

struct PackedGrid {
    QVector<CellPos> cells; // Same length as slots; invalid (-1, -1) if hidden.
    int rows = 0;
};

PackedGrid packHome(const QVector<HomeSlot> &items, int cols, const QSet<QString> &hidden);
QRectF cellBox(const CellPos &cell, qreal colW, int spanW = 1, int spanH = 1);

} // namespace Layout
