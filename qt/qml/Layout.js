.pragma library

// The home grid, as plain functions over the home list so they can be tested on their
// own (tests/layout.test.mjs). A slot is
//   {kind: "app", str: COMPONENT, x, y}
//   {kind: "widget", str: LABEL, wid: HOST_ID, w: COLUMNS (0: whole row), rows, x, y}
// where x, y is the cell it was dropped into (column, row from 0), or -1, -1: placed in
// order into the first free cells that fit it ("dense" packing, so apps fill the cells
// beside a widget and the grid has no holes).

var CELL = 64;   // row height, px
var RGAP = 8;    // gap between rows
var CGAP = 8;    // gap between columns

// Rows of app tiles nearest to a widget height in px, at least one.
function widgetRows(px) {
    return Math.max(1, Math.floor((px + RGAP + (CELL + RGAP) / 2) / (CELL + RGAP)));
}

function slotW(s, cols) {
    if (s.kind !== "widget") return 1;
    return (s.w <= 0 || s.w > cols) ? cols : s.w;
}

function slotH(s) {
    return s.kind === "widget" ? Math.max(1, s.rows | 0) : 1;
}

function pinned(s, cols) {
    return s.x >= 0 && s.y >= 0 && s.x + slotW(s, cols) <= cols;
}

// Where each slot goes: [{col, row, w, h}] (null for a hidden slot), and the rows used,
// at least nrows (the rows that fill the screen).
function pack(home, cols, nrows, hidden) {
    var occ = [];
    function taken(c, r) { return occ[r] !== undefined && occ[r][c] === true; }
    function fits(c, r, w, h) {
        if (c < 0 || c + w > cols) return false;
        for (var y = r; y < r + h; y++)
            for (var x = c; x < c + w; x++)
                if (taken(x, y)) return false;
        return true;
    }
    function mark(c, r, w, h) {
        for (var y = r; y < r + h; y++) {
            if (occ[y] === undefined) occ[y] = [];
            for (var x = c; x < c + w; x++) occ[y][x] = true;
        }
    }
    var cells = new Array(home.length);
    var rows = Math.max(0, nrows | 0);
    var i, s, w, h;
    // Pinned slots first, where they were put (they may overlap each other).
    for (i = 0; i < home.length; i++) {
        s = home[i];
        cells[i] = null;
        if ((hidden && hidden[i]) || !pinned(s, cols)) continue;
        w = slotW(s, cols); h = slotH(s);
        cells[i] = {col: s.x, row: s.y, w: w, h: h};
        mark(s.x, s.y, w, h);
        rows = Math.max(rows, s.y + h);
    }
    // The rest in order, each into the first free cells that fit it.
    for (i = 0; i < home.length; i++) {
        s = home[i];
        if ((hidden && hidden[i]) || cells[i] !== null) continue;
        w = slotW(s, cols); h = slotH(s);
        for (var r = 0; cells[i] === null; r++) {
            for (var c = 0; c + w <= cols; c++) {
                if (fits(c, r, w, h)) {
                    cells[i] = {col: c, row: r, w: w, h: h};
                    mark(c, r, w, h);
                    rows = Math.max(rows, r + h);
                    break;
                }
            }
        }
    }
    return {cells: cells, rows: rows};
}

// A cell's box in px, given the column width.
function box(cell, colW) {
    return {x: cell.col * (colW + CGAP), y: cell.row * (CELL + RGAP),
            w: cell.w * colW + (cell.w - 1) * CGAP, h: cell.h * CELL + (cell.h - 1) * RGAP};
}

// The cell nearest to the top left of a box at x, y (px in the grid).
function cellAt(x, y, colW) {
    return [Math.max(0, Math.round(x / (colW + CGAP))), Math.max(0, Math.round(y / (CELL + RGAP)))];
}

// Slot i pinned with its top left at col, row (kept on the screen's grid).
function pin(home, i, col, row, cols, nrows) {
    var s = home[i];
    var x = col, y = row;
    if (x + slotW(s, cols) > cols) x = cols - slotW(s, cols);
    if (nrows > 0 && y + slotH(s) > nrows) y = nrows - slotH(s);
    s.x = Math.max(0, x);
    s.y = Math.max(0, y);
}

// Unpin the slots under pinned slot i, so they flow into free cells.
function clearCells(home, i, cols) {
    var a = home[i];
    for (var j = 0; j < home.length; j++) {
        var b = home[j];
        if (j === i || b.x < 0) continue;
        if (b.x < a.x + slotW(a, cols) && a.x < b.x + slotW(b, cols)
                && b.y < a.y + slotH(a) && a.y < b.y + slotH(b)) {
            b.x = -1;
            b.y = -1;
        }
    }
}

// Take slot a out and put it back at b, shifting the slots between.
function move(home, a, b) {
    var s = home.splice(a, 1)[0];
    home.splice(b, 0, s);
}

// Resize steps: the number of cells (of size px, gap apart) nearest to want px, kept
// within [min, max] px where it can be (max 0: no limit), and within [lo, hi] cells.
function span(k, size, gap) { return k * size + (k - 1) * gap; }
function fit(want, lo, hi, size, gap, min, max) {
    var k = Math.round((want + gap) / (size + gap));
    while (k < hi && span(k, size, gap) < min - 8) k++;
    while (k > lo && max > 0 && span(k, size, gap) > max + 8) k--;
    return Math.max(lo, Math.min(hi, k));
}

// Columns a new widget of natural width px starts at (0: the whole row).
function widgetCols(px, colW, cols) {
    if (!px) return 0;
    var k = Math.max(1, Math.ceil((px + CGAP - 8) / (colW + CGAP)));
    return k >= cols ? 0 : k;
}
