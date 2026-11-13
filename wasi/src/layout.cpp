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
    auto isFree = [&](int c, int r, int w, int h) -> bool {
        if (c < 0 || c + w > cols || r < 0) return false;
        for (int dr = 0; dr < h; ++dr) {
            const int rr = r + dr;
            if (rr >= static_cast<int>(occ.size())) continue;
            for (int dc = 0; dc < w; ++dc) {
                if (occ[rr][c + dc]) return false;
            }
        }
        return true;
    };
    auto occupy = [&](int c, int r, int w, int h) {
        for (int dr = 0; dr < h; ++dr) {
            const int rr = r + dr;
            ensureRow(rr);
            for (int dc = 0; dc < w; ++dc) {
                occ[rr][c + dc] = true;
            }
            out.rows = std::max(out.rows, rr + 1);
        }
    };

    // Pass 1: place pinned slots first if their area is free.
    QVector<bool> placed(items.size(), false);
    for (int i = 0; i < items.size(); ++i) {
        const auto &s = items[i];
        if (!s.isWidget() && hidden.contains(s.component)) continue;
        const int w = std::clamp(s.w, 1, cols);
        const int h = std::max(1, s.h);
        if (s.x >= 0 && s.y >= 0 && isFree(s.x, s.y, w, h)) {
            occupy(s.x, s.y, w, h);
            out.cells[i] = {s.x, s.y};
            placed[i] = true;
        }
    }

    // Pass 2: pack remaining visible slots into the earliest free area.
    for (int i = 0; i < items.size(); ++i) {
        const auto &s = items[i];
        if (placed[i] || (!s.isWidget() && hidden.contains(s.component))) continue;
        const int w = std::clamp(s.w, 1, cols);
        const int h = std::max(1, s.h);
        int r = 0, c = 0;
        while (!isFree(c, r, w, h)) {
            ++c;
            if (c + w > cols) {
                c = 0;
                ++r;
            }
        }
        occupy(c, r, w, h);
        out.cells[i] = {c, r};
    }

    return out;
}

QRectF cellBox(const CellPos &cell, qreal colW, int spanW, int spanH) {
    const qreal x = cell.col * (colW + kColGap);
    const qreal y = cell.row * (kCellH + kRowGap);
    const qreal w = spanW * colW + std::max(0, spanW - 1) * kColGap;
    const qreal h = spanH * kCellH + std::max(0, spanH - 1) * kRowGap;
    return QRectF(x, y, w, h);
}

} // namespace Layout
