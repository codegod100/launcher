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
    QString component;
    int x = -1;
    int y = -1;
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
QRectF cellBox(const CellPos &cell, qreal colW);

} // namespace Layout
