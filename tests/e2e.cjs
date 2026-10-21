// Browser smoke test for the example apps.
//   pixi run test   (or: pixi run serve &   then   node tests/e2e.cjs)
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
  assert.equal(await p.getAttribute(".switch", "aria-checked"), "true");
  await p.click("text=Auto-increment"); await tick();
  assert.equal(await p.getAttribute(".switch", "aria-checked"), "false");
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
  await p.click(".item:nth-child(2) .checkbox"); await tick();
  assert.equal(await p.textContent(".footer .badge"), "2 left");
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

  // bench: keyed rows
  await p.goto(BASE + "/examples/bench/");
  await p.waitForSelector("#run");
  const rows = () => p.$$eval("tbody tr", (rs) => rs.length);
  const ids = () => p.$$eval("tbody td.id", (ts) => ts.map((t) => Number(t.textContent)));
  const op = async (sel) => { await p.click(sel); await p.waitForTimeout(60); };
  await op("#run");
  assert.equal(await rows(), 1000);
  assert.equal(await p.textContent(".rows"), "1000 rows");
  await op("#update");
  assert.match(await p.textContent("tbody tr:nth-child(1) td.lbl"), / !!!$/);
  assert.doesNotMatch(await p.textContent("tbody tr:nth-child(2) td.lbl"), /!!!/);
  // keyed patching must reuse row nodes: tag them, swap, and check identity
  await p.$$eval("tbody tr", (rs) => rs.forEach((r, i) => { r.__tag = i; }));
  const before = await ids();
  await op("#swaprows");
  const after = await ids();
  assert.equal(after[1], before[998]);
  assert.equal(after[998], before[1]);
  assert.deepEqual(await p.$$eval("tbody tr", (rs) => [rs[0].__tag, rs[1].__tag, rs[2].__tag, rs[998].__tag]), [0, 998, 2, 1]);
  await op("tbody tr:nth-child(3) td.lbl a");
  assert.ok(await p.$("tbody tr:nth-child(3).danger"));
  assert.equal(await p.$$eval("tr.danger", (rs) => rs.length), 1);
  await op("tbody tr:nth-child(5) a.x");
  assert.equal(await rows(), 999);
  assert.deepEqual(await ids(), after.filter((_, i) => i !== 4));
  assert.equal(await p.$eval("tbody tr:nth-child(5)", (r) => r.__tag), 5);
  await op("#runlots");
  assert.equal(await rows(), 10000);
  await op("#add");
  assert.equal(await rows(), 11000);
  await op("#clear");
  assert.equal(await rows(), 0);
  const suite = await p.evaluate(() => window.ultraBench.runSuite(1));
  assert.equal(suite.length, 9);
  assert.ok(suite.every((r) => r.ms > 0));
  console.log("bench ok:", suite.map((r) => `${r.name} ${r.ms.toFixed(0)}ms`).join(", "));

  // life: animation loop via frame()
  await p.goto(BASE + "/examples/life/");
  await p.waitForSelector(".grid i");
  const gen = async () => Number((await p.textContent(".stats")).match(/gen (\d+)/)[1]);
  const g0 = await gen();
  await p.waitForTimeout(500);
  assert.ok((await gen()) > g0 + 5, "animation should advance");
  assert.equal(await p.$$eval(".grid i", (cs) => cs.length), 10000);
  await op("#play");
  const g1 = await gen();
  await p.waitForTimeout(200);
  assert.equal(await gen(), g1, "paused");
  await op("#step");
  assert.equal(await gen(), g1 + 1);
  await op("text=Glider guns");
  assert.match(await p.textContent(".stats"), /gen 0 · pop 72 ·/);
  await op("text=50²");
  assert.equal(await p.$$eval(".grid i", (cs) => cs.length), 2500);
  console.log("life ok");

  // ui: component gallery, every widget wired to live state
  await p.goto(BASE + "/examples/ui/");
  await p.evaluate(() => localStorage.clear());
  await p.reload();
  await p.waitForSelector(".card");
  await p.click("text=Destructive"); await p.click("text=Large"); await tick();
  assert.equal(await p.getAttribute("#preview", "class"), "btn destructive lg");
  await p.click("#preview"); await p.click("#preview"); await tick();
  assert.equal(await p.textContent(".preview .badge"), "2");
  // profile: Save is disabled until there is a name and the terms are accepted
  assert.ok(await p.$("#save[disabled]"));
  await p.fill("input[placeholder='Ada Lovelace']", "Ada"); await tick();
  assert.equal(await p.textContent(".hello-name"), "Ada");
  assert.ok(await p.$("#save[disabled]"));
  await p.click("text=Accept terms"); await tick();
  assert.ok(!(await p.$("#save[disabled]")));
  await p.press("input[placeholder='Ada Lovelace']", "Enter"); await tick();
  assert.match(await p.textContent(".toast"), /Saved Ada\./);
  await p.waitForTimeout(2700);
  assert.ok(!(await p.$(".toast")), "toast hides itself");
  await p.click(".field >> text=Email notifications"); await tick();
  assert.match(await p.textContent(".hello"), /muted/);
  // tabs
  await p.click("[role=tab] >> text=Password"); await tick();
  await p.fill("input[type=password]", "abc"); await tick();
  assert.equal(await p.textContent("[role=tabpanel] .badge"), "Weak");
  await p.fill("input[type=password]", "correct horse"); await tick();
  assert.equal(await p.textContent("[role=tabpanel] .badge"), "Strong");
  await p.click("[role=tab] >> text=Billing"); await tick();
  assert.equal(await p.textContent(".total"), "$36 / month");
  await p.click("text=Yearly"); await p.click("[role=tabpanel] >> text=+"); await tick();
  assert.equal(await p.textContent(".total"), "$480 / year");
  // progress animation
  await p.click("#play"); await p.waitForTimeout(400);
  const lvl = Number(await p.textContent("#play + .badge"));
  assert.ok(lvl > 30, "progress should advance");
  await p.click("#play");
  // table: search, filter, sort, select, delete with undo
  const invs = () => p.$$eval("tbody td.mono", (ts) => ts.map((t) => t.textContent));
  await p.fill("input[placeholder^=Search]", "pay"); await tick();
  assert.deepEqual(await invs(), ["INV002", "INV005"]);
  await p.fill("input[placeholder^=Search]", ""); await p.click('.toggle >> text="Pending"'); await tick();
  assert.deepEqual(await invs(), ["INV002", "INV006"]);
  await p.click('text="All"'); await p.click("#sort"); await tick();
  assert.deepEqual(await invs(), ["INV004", "INV003", "INV001", "INV006", "INV002", "INV005"]);
  assert.equal(await p.textContent("tfoot td.num"), "$1450.00");
  await p.click("tbody td >> text=INV005"); await p.click("tbody tr:nth-child(6) button.badge"); await tick();
  assert.equal(await p.textContent("tbody tr:nth-child(6) .badge"), "Pending");
  assert.equal(await p.getAttribute("tbody tr:nth-child(6)", "aria-selected"), "true");
  await p.click("#delete"); await tick();
  await p.keyboard.press("Escape"); await tick();
  assert.ok(!(await p.$(".dialog")), "Escape closes the dialog");
  await p.click("#delete"); await tick();
  await p.click(".dialog-overlay", { position: { x: 10, y: 10 } }); await tick();
  assert.ok(!(await p.$(".dialog")), "backdrop closes the dialog");
  await p.click("#delete"); await tick();
  await p.click(".dialog .btn.destructive"); await tick();
  assert.equal((await invs()).length, 5);
  assert.ok(await p.$("#delete[disabled]"));
  await p.click("text=Undo delete of INV005"); await tick();
  assert.equal((await invs()).length, 6);
  // theme persists
  await p.click("text=Dark"); await tick();
  await p.reload(); await p.waitForSelector(".page");
  assert.ok(await p.$(".page.dark"));
  console.log("ui ok");

  await b.close();
})().catch((e) => { console.error(e); process.exit(1); });
