// node qt/tests/layout.test.mjs: checks the home grid packing in qml/Layout.js.
import { readFileSync } from "node:fs";
import assert from "node:assert/strict";

const src = readFileSync(new URL("../qml/Layout.js", import.meta.url), "utf8").replace(".pragma library", "");
const L = new Function(src + "; return {widgetRows, slotW, slotH, pack, box, cellAt, pin, clearCells, move, fit, widgetCols};")();

const app = (x = -1, y = -1) => ({ kind: "app", str: "a", x, y });
const widget = (w, rows, x = -1, y = -1) => ({ kind: "widget", str: "W", wid: 1, w, rows, x, y });
const cells = (home, cols = 5, nrows = 0) => L.pack(home, cols, nrows).cells.map((c) => c && [c.col, c.row, c.w, c.h]);

// Apps fill rows left to right.
assert.deepEqual(cells([app(), app(), app(), app(), app(), app()]),
  [[0, 0, 1, 1], [1, 0, 1, 1], [2, 0, 1, 1], [3, 0, 1, 1], [4, 0, 1, 1], [0, 1, 1, 1]]);
// A whole-row widget takes its rows; apps after it go below.
assert.deepEqual(cells([widget(0, 2), app()]), [[0, 0, 5, 2], [0, 2, 1, 1]]);
// Dense: apps fill the cells beside a narrow widget, before and after it in order.
assert.deepEqual(cells([widget(3, 2), app(), app(), app(), app(), app()]),
  [[0, 0, 3, 2], [3, 0, 1, 1], [4, 0, 1, 1], [3, 1, 1, 1], [4, 1, 1, 1], [0, 2, 1, 1]]);
// A widget too wide for what's left of a row starts the next one, and apps backfill.
assert.deepEqual(cells([app(), app(), app(), widget(3, 1), app()]),
  [[0, 0, 1, 1], [1, 0, 1, 1], [2, 0, 1, 1], [0, 1, 3, 1], [3, 0, 1, 1]]);
// Pinned slots keep their cell; the rest flow around them.
assert.deepEqual(cells([app(), app(2, 3), app()]), [[0, 0, 1, 1], [2, 3, 1, 1], [1, 0, 1, 1]]);
// Rows: at least nrows, more if the slots need them.
assert.equal(L.pack([app()], 5, 9).rows, 9);
assert.equal(L.pack([app(0, 12)], 5, 9).rows, 13);
// A pin that no longer fits the columns flows instead.
assert.deepEqual(cells([widget(3, 1, 3, 0)], 5), [[0, 0, 3, 1]]);
// Hidden slots (apps no longer installed) take no cell.
assert.deepEqual(L.pack([app(), app()], 5, 0, [true, false]).cells.map((c) => c && c.col), [null, 0]);

// Pinning stays on the grid; dropping a pinned slot moves pinned slots off its cells.
{
  const h = [widget(2, 2), app(1, 1), app(4, 4)];
  L.pin(h, 0, 4, 9, 5, 8);
  assert.deepEqual([h[0].x, h[0].y], [3, 6]);
  L.pin(h, 0, 0, 0, 5, 8);
  L.clearCells(h, 0, 5);
  assert.deepEqual([h[1].x, h[1].y, h[2].x, h[2].y], [-1, -1, 4, 4]);
}
// Moving in the order.
{
  const h = ["a", "b", "c", "d"];
  L.move(h, 0, 2);
  assert.deepEqual(h, ["b", "c", "a", "d"]);
  L.move(h, 3, 0);
  assert.deepEqual(h, ["d", "b", "c", "a"]);
}
// Widget heights in px to rows (64px rows, 8px gaps), as the old launcher stored them.
assert.deepEqual([40, 64, 100, 136, 208, 420].map(L.widgetRows), [1, 1, 2, 2, 3, 6]);
// Resize steps snap to cells within the widget's limits.
assert.equal(L.fit(150, 1, 5, 64, 8, 0, 0), 2);
assert.equal(L.fit(10, 1, 5, 64, 8, 200, 0), 3);
assert.equal(L.fit(400, 1, 5, 64, 8, 0, 150), 2);
// A new widget's columns from its natural width.
assert.equal(L.widgetCols(0, 70, 5), 0);
assert.equal(L.widgetCols(110, 70, 5), 2);
assert.equal(L.widgetCols(400, 70, 5), 0);
// Boxes and cells in px.
assert.deepEqual(L.box({ col: 1, row: 2, w: 2, h: 1 }, 70), { x: 78, y: 144, w: 148, h: 64 });
assert.deepEqual(L.cellAt(80, 150, 70), [1, 2]);
console.log("layout ok");
