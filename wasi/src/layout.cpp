#include "layout.h"

#include <algorithm>
#include <vector>

namespace Layout {

PackedGrid packHome(const QVector<HomeSlot> &items, int cols, const QSet<QString> &hidden) {
    PackedGrid out;
    out.cells.resize(items.size(), {-1, -1});
    if (cols <= 0) return out;

    // Occupancy grid grows dynamically as rows are needed.
    std::vector<std::vector<bool>> occ;
    auto ensureRow = [&](int r) {
        while (static_cast<int>(occ.size()) <= r) occ.emplace_back(cols, false);
    };
    auto isFree = [&](int c, int r) -> bool {
        if (c < 0 || c >= cols || r < 0) return false;
        if (r >= static_cast<int>(occ.size())) return true;
        return !occ[r][c];
    };
    auto occupy = [&](int c, int r) {
        ensureRow(r);
        occ[r][c] = true;
        out.rows = std::max(out.rows, r + 1);
    };

    // Pass 1: place pinned slots first if their cell is free.
    QVector<bool> placed(items.size(), false);
    for (int i = 0; i < items.size(); ++i) {
        const auto &s = items[i];
        if (hidden.contains(s.component)) continue;
        if (s.x >= 0 && s.x < cols && s.y >= 0 && isFree(s.x, s.y)) {
            occupy(s.x, s.y);
            out.cells[i] = {s.x, s.y};
            placed[i] = true;
        }
    }

    // Pass 2: pack remaining visible slots into the earliest free cell.
    int cursorR = 0;
    int cursorC = 0;
    for (int i = 0; i < items.size(); ++i) {
        if (placed[i] || hidden.contains(items[i].component)) continue;
        while (!isFree(cursorC, cursorR)) {
            ++cursorC;
            if (cursorC >= cols) {
                cursorC = 0;
                ++cursorR;
            }
        }
        occupy(cursorC, cursorR);
        out.cells[i] = {cursorC, cursorR};
    }

    return out;
}

QRectF cellBox(const CellPos &cell, qreal colW) {
    const qreal x = cell.col * (colW + kColGap);
    const qreal y = cell.row * (kCellH + kRowGap);
    return QRectF(x, y, colW, kCellH);
}

} // namespace Layout
