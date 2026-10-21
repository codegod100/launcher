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
  assert.match(await p.textContent(".clock .time"), /^\d{1,2}:\d\d$/);
  const longpress = async (sel) => {
    const r = await p.locator(sel).first().boundingBox();
    await p.mouse.move(r.x + r.width / 2, r.y + r.height / 2);
    await p.mouse.down(); await p.waitForTimeout(650); await p.mouse.up(); await settle();
  };
  const homeApps = () => p.$$eval(".slots .name", (ns) => ns.map((n) => n.textContent));
  // long press on the blank home: add apps through the drawer's pick mode
  await longpress(".hint");
  await p.click(".sheet-item >> text=Add apps"); await settle();
  for (const i of [4, 8, 12]) await p.click(`.apps .tile:nth-child(${i})`);
  await settle();
  assert.equal(await p.$$eval(".apps .tile.on", (ts) => ts.length), 3);
  await p.click(".drawer-head >> text=Done"); await settle();
  assert.deepEqual(await homeApps(), ["Chrome", "Gmail", "Phone"]);
  // add a widget
  await longpress(".clock");
  await p.click(".sheet-item >> text=Add widget");
  await p.waitForSelector(".widget-list li");
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
  // swipe down opens the drawer (and takes the widget off screen), search, long press adds
  const swipe = (y0, y1) => p.evaluate(([y0, y1]) => {
    const at = (y) => new Touch({ identifier: 1, target: document.body, clientX: 200, clientY: y });
    dispatchEvent(new TouchEvent("touchstart", { touches: [at(y0)], changedTouches: [at(y0)] }));
    dispatchEvent(new TouchEvent("touchend", { touches: [], changedTouches: [at(y1)] }));
  }, [y0, y1]);
  await swipe(200, 500);
  await p.waitForSelector(".drawer", { timeout: 2000 });
  assert.equal(await p.$$eval("[data-wid]", (ws) => ws.length), 0);
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
  await longpress(".widget-slot");
  await p.click(".sheet-item >> text=Remove widget"); await settle();
  assert.ok(!(await p.$(".widget-slot")));
  await p.evaluate(() => { window.ultraHome.reload(); }); await settle();
  assert.equal(await p.$$eval(".slots > li", (ls) => ls.length), 4);
  console.log("launcher ok");

  await b.close();
})().catch((e) => { console.error(e); process.exit(1); });
