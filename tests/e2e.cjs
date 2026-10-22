// Browser smoke test for the launcher's web UI, against the demo apps.json and widgets.json.
//   pixi run test   (or: pixi run serve &   then   node tests/e2e.cjs)
const assert = require("assert");
const { chromium } = require(process.env.PLAYWRIGHT || "playwright");
const BASE = process.env.BASE || "http://localhost:8000";

(async () => {
  const b = await chromium.launch();
  const p = await b.newPage();
  p.on("pageerror", (e) => { console.error("page error:", e.message); process.exitCode = 1; });

  const settle = () => p.waitForTimeout(250); // renders land on the next frame, which can lag here
  await p.emulateMedia({ reducedMotion: "reduce" }); // no sliding drawer under the pointer
  await p.goto(BASE + "/");
  await p.evaluate(() => localStorage.clear());
  await p.reload();
  await p.waitForSelector(".hint");
  assert.ok(!(await p.$(".clock")), "home has no built-in clock");
  const longpress = async (sel, fy = 0.5) => {
    const r = await p.locator(sel).first().boundingBox();
    await p.mouse.move(r.x + r.width / 2, r.y + r.height * fy);
    await p.mouse.down(); await p.waitForTimeout(650); await p.mouse.up(); await settle();
  };
  // the home menu's update check goes to the shell and closes the menu
  await longpress(".hint");
  await p.click(".sheet-item >> text=Check for updates"); await settle();
  assert.equal(await p.textContent("#toast"), "Check for updates");
  assert.ok(!(await p.$(".sheet")), "the menu closes");
  const homeApps = () => p.$$eval(".slots .name", (ns) => ns.map((n) => n.textContent));
  // long press on the blank home: add apps through the drawer's pick mode
  await longpress(".hint");
  await p.click(".sheet-item >> text=Add apps"); await settle();
  for (const i of [4, 8, 12]) await p.click(`.apps .tile:nth-child(${i})`);
  await settle();
  assert.equal(await p.$$eval(".apps .tile.on", (ts) => ts.length), 3);
  await p.click(".drawer-head >> text=Done"); await settle();
  assert.deepEqual(await homeApps(), ["Chrome", "Gmail", "Phone"]);
  // add a widget: long press on blank home, below the apps
  await longpress(".home", 0.95);
  await p.click(".sheet-item >> text=Add widget");
  await p.waitForSelector(".widget-list li");
  // search the picker by widget name and by the app it comes from
  const widgetNames = () => p.$$eval(".widget-list .widget-name", (ns) => ns.map((n) => n.firstChild.textContent));
  await p.fill(".widget-search", "cal"); await settle();
  assert.deepEqual(await widgetNames(), ["Calendar"]);
  await p.fill(".widget-search", "goo"); await settle();
  assert.deepEqual(await widgetNames(), ["Weather"]);
  await p.fill(".widget-search", "zzz"); await settle();
  assert.equal(await p.textContent(".sheet .empty"), "No widgets match.");
  await p.fill(".widget-search", ""); await settle();
  await p.click(".widget-list li:first-child"); await settle();
  assert.equal(await p.getAttribute(".widget-slot", "data-wid"), "100");
  assert.equal(await p.textContent(".widget-label"), "Clock");
  // hold a home app and drag it: reorder
  const drag = async (from, to) => {
    const a = await p.locator(from).first().boundingBox(), z = await p.locator(to).first().boundingBox();
    await p.mouse.move(a.x + a.width / 2, a.y + a.height / 2);
    await p.mouse.down(); await p.waitForTimeout(650); await settle();
    assert.ok(await p.$(".slots > .lifted"), "a long press lifts the slot");
    await p.mouse.move(z.x + z.width / 2, z.y + z.height / 2, { steps: 5 }); await settle();
    await p.mouse.up(); await settle();
  };
  await drag(".slots .tile:nth-child(2)", ".slots .tile:nth-child(1)");
  assert.deepEqual(await homeApps(), ["Gmail", "Chrome", "Phone"]);
  assert.ok(!(await p.$(".sheet")), "a drag doesn't open the menu");
  assert.ok(!(await p.$(".slots > .lifted")));
  await drag(".slots .tile:nth-child(1)", ".slots .tile:nth-child(3)");
  assert.deepEqual(await homeApps(), ["Chrome", "Phone", "Gmail"]);
  // hold without moving: the menu
  await longpress(".slots .tile:nth-child(3)");
  await p.click(".sheet-item >> text=Move earlier"); await settle();
  assert.deepEqual(await homeApps(), ["Chrome", "Gmail", "Phone"]);
  await longpress(".slots .tile:nth-child(1)");
  await p.click(".sheet-item >> text=Move later"); await settle();
  assert.deepEqual(await homeApps(), ["Gmail", "Chrome", "Phone"]);
  // the grid runs down the screen; an app dropped in an empty cell there stays in it,
  // and the rest stay packed
  const rows = await p.$eval(".slots", (s) => getComputedStyle(s).gridTemplateRows.split(" ").length);
  assert.ok(rows >= 5, `rows fill the screen: ${rows}`);
  const tileAt = (n) => p.locator(`.slots .tile:has-text("${n}")`).boundingBox();
  const g0 = await tileAt("Gmail"), c0 = await tileAt("Chrome"), pitch = 64 + 8;
  await p.mouse.move(g0.x + g0.width / 2, g0.y + g0.height / 2);
  await p.mouse.down(); await p.waitForTimeout(650); await settle();
  await p.mouse.move(g0.x + g0.width / 2 + 10, g0.y + g0.height / 2 + 4 * pitch, { steps: 6 }); await settle();
  await p.mouse.up(); await settle();
  const g1 = await tileAt("Gmail"), c1 = await tileAt("Chrome");
  assert.ok(Math.abs(g1.y - g0.y - 4 * pitch) < 3, `left four rows down: ${g1.y - g0.y}`);
  assert.equal(Math.round(c1.x), Math.round(g0.x), "Chrome moves up into the packed first cell");
  await p.reload(); await p.waitForSelector(".slots .tile");
  assert.ok(Math.abs((await tileAt("Gmail")).y - g1.y) < 3, "its cell is saved");
  // dragging it back over an app puts it back in the packed order
  const c2 = await tileAt("Chrome");
  await p.mouse.move(g1.x + g1.width / 2, g1.y + g1.height / 2);
  await p.mouse.down(); await p.waitForTimeout(650); await settle();
  await p.mouse.move(c2.x + c2.width / 2, c2.y + c2.height / 2, { steps: 6 }); await settle();
  await p.mouse.up(); await settle();
  assert.deepEqual(await homeApps(), ["Gmail", "Chrome", "Phone"]);
  assert.ok(Math.abs((await tileAt("Gmail")).y - c0.y) < 3, "back in the first row");
  await p.mouse.click(5, 300); await settle(); // a tap on empty home ends the drag gesture
  // swipe down opens the drawer (and takes the widget off screen), search, long press adds
  const swipe = (y0, y1) => p.evaluate(([y0, y1]) => {
    const at = (y) => new Touch({ identifier: 1, target: document.body, clientX: 200, clientY: y });
    dispatchEvent(new TouchEvent("touchstart", { touches: [at(y0)], changedTouches: [at(y0)] }));
    dispatchEvent(new TouchEvent("touchend", { touches: [], changedTouches: [at(y1)] }));
  }, [y0, y1]);
  await swipe(200, 500);
  await p.waitForSelector(".drawer", { timeout: 2000 });
  assert.equal(await p.$$eval("[data-wid]", (ws) => ws.length), 0);
  assert.ok(await p.$eval(".drawer input", (i) => i === document.activeElement), "opening the drawer focuses its search");
  await p.fill(".drawer input", "ma"); await settle();
  assert.deepEqual(await p.$$eval(".apps .name", (ns) => ns.map((n) => n.textContent)), ["Gmail", "Maps"]);
  await p.press(".drawer input", "Enter"); await settle();
  assert.equal(await p.textContent("#toast"), "Open com.example.gmail/.MainActivity");
  assert.ok(!(await p.$(".drawer")), "launching closes the drawer");
  await swipe(200, 500);
  await p.waitForSelector(".drawer .tile", { timeout: 2000 });
  await longpress(".apps .tile:first-child");
  assert.equal(await p.textContent(".notice"), "Added to home");
  await swipe(600, 200);
  await p.waitForSelector(".drawer", { state: "detached", timeout: 2000 }); // swipe up closes it
  assert.deepEqual(await homeApps(), ["Gmail", "Chrome", "Phone", "Calculator"]);
  // persisted; tap launches; remove
  await p.reload(); await p.waitForSelector(".slots .tile");
  assert.equal(await p.$$eval(".slots > li", (ls) => ls.length), 5);
  await p.click(".slots .tile:first-child"); await settle();
  assert.equal(await p.textContent("#toast"), "Open com.example.gmail/.MainActivity");
  // hold a widget without moving: resize handles; drag them to snap it to the grid
  await longpress(".widget-slot");
  assert.ok(!(await p.$(".sheet")), "a widget gets handles, not the menu");
  assert.ok(await p.$(".widget-slot.resizing .resize-handle[data-edge=rb]"));
  const size = () => p.$eval(".widget-slot", (w) => [Math.round(w.getBoundingClientRect().width), Math.round(w.getBoundingClientRect().height)]);
  const [w0] = await size();
  const tile = await p.locator(".slots .tile").first().boundingBox();
  const hd = await p.locator(".resize-handle[data-edge=rb]").boundingBox();
  await p.mouse.move(hd.x + hd.width / 2, hd.y + hd.height / 2);
  await p.mouse.down();
  const ws = await p.locator(".widget-slot").boundingBox();
  await p.mouse.move(ws.x + tile.width * 2.1, ws.y + 140, { steps: 8 }); await settle();
  await p.mouse.up(); await settle();
  const [w1, h1] = await size();
  assert.ok(w1 < w0 && Math.abs(w1 - (2 * tile.width + 8)) < 3, `two columns wide: ${w1} (tile ${tile.width})`);
  assert.equal(h1, 2 * 64 + 8, "snapped to two whole rows of tiles");
  assert.ok(await p.$(".widget-slot.resizing"), "letting go of a handle keeps the handles");
  await p.click(".resize-bar >> text=Done"); await settle();
  assert.ok(!(await p.$(".resize-handle")));
  await p.reload(); await p.waitForSelector(".widget-slot");
  assert.deepEqual(await size(), [w1, h1], "the size is saved");
  // while it shows handles, dragging the widget itself moves it, without another long press
  await longpress(".widget-slot");
  const atStart = () => p.$eval(".widget-slot", (w) => {
    const r = w.getBoundingClientRect(), s = w.parentElement.getBoundingClientRect();
    return Math.abs(r.left - s.left) < 2 && Math.abs(r.top - s.top) < 2;
  });
  assert.ok(!(await atStart()));
  const wb = await p.locator(".widget-slot").boundingBox(), first = await p.locator(".slots > li").first().boundingBox();
  await p.mouse.move(wb.x + wb.width / 2, wb.y + wb.height / 2);
  await p.mouse.down(); await settle();
  assert.ok(await p.$(".widget-slot.lifted"), "pressing a widget with handles picks it up");
  await p.mouse.move(first.x + first.width / 2, first.y + first.height / 2, { steps: 8 }); await settle();
  await p.mouse.up(); await settle();
  assert.ok(await atStart(), "dragged onto the top row, first cell");
  // the apps flow into the cells beside it: a 2x2 widget in 5 columns leaves room for all four
  const beside = await p.$eval(".widget-slot", (w) => {
    const r = w.getBoundingClientRect();
    return [...document.querySelectorAll(".slots .tile")].filter((t) => {
      const b = t.getBoundingClientRect(); return b.top >= r.top - 1 && b.bottom <= r.bottom + 1;
    }).length;
  });
  assert.equal(beside, 4, "apps fill the cells beside the widget");
  assert.ok(await p.$(".widget-slot.resizing"), "it keeps its handles after the move");
  assert.ok(!(await p.$(".sheet")));
  await p.click(".resize-bar >> text=Remove"); await settle();
  assert.ok(!(await p.$(".widget-slot")));
  await p.evaluate(() => { window.ultraHome.reload(); }); await settle();
  assert.equal(await p.$$eval(".slots > li", (ls) => ls.length), 4);
  console.log("launcher ok");

  await b.close();
})().catch((e) => { console.error(e); process.exit(1); });
