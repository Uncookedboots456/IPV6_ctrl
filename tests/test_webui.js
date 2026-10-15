const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const source = fs.readFileSync(path.join(__dirname, "../webroot/app.js"), "utf8");

function page(exec) {
  const elements = new Map();
  const timers = new Map();
  const context = {
    window: exec ? { ksu: { exec } } : {},
    document: {
      querySelector(selector) {
        if (!elements.has(selector)) elements.set(selector, { addEventListener() {} });
        return elements.get(selector);
      },
    },
    setTimeout(callback) { const id = timers.size; timers.set(id, callback); return id; },
    clearTimeout(id) { timers.delete(id); },
  };
  vm.runInNewContext(source, context, { filename: "app.js" });
  return { context, elements, timers };
}

test("native bridge forwards results and removes callbacks", async () => {
  const view = page((command, options, name) => {
    assert.equal(command, "echo ok");
    assert.equal(options, "{}");
    view.context.window[name](0, "ok", "");
  });
  const result = await view.context.runShell("echo ok");
  assert.equal(result.errno, 0);
  assert.equal(result.stdout, "ok");
  assert.deepEqual(Object.keys(view.context.window), ["ksu"]);
  assert.equal(view.timers.size, 0);
});

test("bridge errors reject and remove callbacks", async () => {
  const view = page(() => { throw new Error("bridge failed"); });
  await assert.rejects(view.context.runShell("status"), /bridge failed/);
  assert.deepEqual(Object.keys(view.context.window), ["ksu"]);
  assert.equal(view.timers.size, 0);
});

test("missing callback times out and releases UI buttons", async () => {
  const view = page(() => {});
  const request = view.context.refreshStatus();
  assert.equal(view.elements.get("#refreshBtn").disabled, true);
  for (const callback of [...view.timers.values()]) callback();
  await request;
  assert.equal(view.elements.get("#refreshBtn").disabled, false);
  assert.equal(view.elements.get("#stateBadge").textContent, "Error");
  assert.match(view.elements.get("#output").textContent, /timed out/);
  assert.deepEqual(Object.keys(view.context.window), ["ksu"]);
});

test("ordinary browser preview reports unavailable bridge", async () => {
  const view = page();
  await assert.rejects(view.context.runShell("status"), /API unavailable/);
});
