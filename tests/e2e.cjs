// Browser smoke test for the example apps.
//   python3 tools/ultra.py serve &   then   node tests/e2e.cjs
const assert = require("assert");
const { chromium } = require(process.env.PLAYWRIGHT || "playwright");
const BASE = process.env.BASE || "http://localhost:8000";

(async () => {
  const b = await chromium.launch();
  const p = await b.newPage();
  p.on("pageerror", (e) => { console.error("page error:", e.message); process.exitCode = 1; });
  const tick = () => p.waitForTimeout(40);
  const labels = () => p.$$eval(".item label", (ls) => ls.map((l) => l.textContent));

  // counter
  await p.goto(BASE + "/examples/counter/");
  await p.waitForSelector(".count");
  const count = async () => Number((await p.textContent(".count")).replace("−", "-"));
  assert.equal(await count(), 0);
  await p.click("text=+"); await p.click("text=+"); await tick();
  assert.equal(await count(), 2);
  await p.click("text=×10"); await p.click("text=−"); await tick();
  assert.equal(await count(), -8);
  assert.ok(await p.$(".count.neg"));
  await p.click("text=Auto-increment"); await p.waitForTimeout(1200);
  assert.ok((await count()) > -8);
  await p.click("text=Stop auto");
  console.log("counter ok");

  // todo
  await p.goto(BASE + "/examples/todo/");
  await p.evaluate(() => localStorage.clear());
  await p.reload();
  await p.waitForSelector("input.new");
  for (const s of ["Write LPython", "Compile to wasm", "Ship it"]) {
    await p.fill("input.new", s);
    await p.press("input.new", "Enter");
  }
  await tick();
  assert.deepEqual(await labels(), ["Write LPython", "Compile to wasm", "Ship it"]);
  assert.equal(await p.inputValue("input.new"), "");
  await p.click(".item:nth-child(2) input"); await tick();
  assert.equal(await p.textContent(".footer span"), "2 left");
  await p.click("text=Active"); await tick();
  assert.deepEqual(await labels(), ["Write LPython", "Ship it"]);
  await p.click("text=All"); await tick();
  await p.reload(); await p.waitForSelector(".item");
  assert.deepEqual(await labels(), ["Write LPython", "Compile to wasm", "Ship it"]);
  assert.ok(await p.$(".item:nth-child(2).done"));
  await p.click(".item:first-child .x", { force: true }); await tick();
  await p.click("text=Clear done"); await tick();
  assert.deepEqual(await labels(), ["Ship it"]);
  console.log("todo ok");

  await b.close();
})().catch((e) => { console.error(e); process.exit(1); });
