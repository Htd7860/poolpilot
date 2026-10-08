const { chromium } = require("playwright");
const assert = require("node:assert/strict");
const { spawn } = require("node:child_process");
const { createHash } = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");

(async () => {
  const root = path.resolve(__dirname, "..");
  const version = JSON.parse(
    fs.readFileSync(path.join(root, "package.json")),
  ).version;
  const java = process.env.JAVA_HOME
    ? path.join(
        process.env.JAVA_HOME,
        "bin",
        process.platform === "win32" ? "java.exe" : "java",
      )
    : "java";
  const reader = "browser-reader-test-token",
    writer = "browser-writer-test-token";
  const hash = (value) => createHash("sha256").update(value).digest("hex");
  const processArgs = [
    "-jar",
    path.join(
      root,
      "poolpilot-demo",
      "target",
      `poolpilot-demo-${version}.jar`,
    ),
    "--server.port=0",
    "--poolpilot.persistence.enabled=false",
    "--poolpilot.admin.token=",
    "--poolpilot.admin.users[0].name=observer",
    `--poolpilot.admin.users[0].token-sha256=${hash(reader)}`,
    "--poolpilot.admin.users[0].role=READER",
    "--poolpilot.admin.users[1].name=oncall",
    `--poolpilot.admin.users[1].token-sha256=${hash(writer)}`,
    "--poolpilot.admin.users[1].role=OPERATOR",
    "--poolpilot.timeouts.interval-millis=25",
    "--poolpilot.timeouts.queue-millis=50",
    "--poolpilot.timeouts.run-millis=50",
  ];
  const server = spawn(java, processArgs, {
    cwd: root,
    windowsHide: true,
    stdio: ["ignore", "pipe", "pipe"],
  });
  let output = "",
    browser,
    readyTimer;
  const checks = [],
    errors = [];
  const until = async (condition) => {
    const deadline = Date.now() + 10000;
    while (Date.now() < deadline) {
      if (await condition()) return;
      await new Promise((resolve) => setTimeout(resolve, 100));
    }
    throw new Error("Governance condition timed out");
  };
  try {
    const base = await new Promise((resolve, reject) => {
      readyTimer = setTimeout(
        () => reject(new Error("Demo startup timed out\n" + output)),
        30000,
      );
      const capture = (chunk) => {
        output = (output + chunk.toString()).slice(-16000);
        const match = output.match(/Tomcat started on port (\d+)/);
        if (match) {
          clearTimeout(readyTimer);
          resolve("http://127.0.0.1:" + match[1]);
        }
      };
      server.stdout.on("data", capture);
      server.stderr.on("data", capture);
      server.once("error", reject);
      server.once("exit", (code) =>
        reject(new Error(`Demo exited ${code}\n${output}`)),
      );
    });
    browser = await chromium.launch({
      channel:
        process.env.POOLPILOT_BROWSER === "chromium"
          ? undefined
          : process.env.POOLPILOT_BROWSER || "chrome",
      headless: true,
    });
    const context = await browser.newContext({
      viewport: { width: 1440, height: 1050 },
      locale: "en-US",
    });
    const page = await context.newPage();
    page.on("pageerror", (error) => errors.push(error.message));
    const api = (token, endpoint, options = {}) =>
      context.request.fetch(base + "/poolpilot/api/" + endpoint, {
        ...options,
        headers: { "X-PoolPilot-Token": token, ...(options.headers || {}) },
      });
    await page.goto(base + "/poolpilot/index.html");
    await page.locator("#token").fill(reader);
    await page.locator("#login-form button.primary").click();
    await page.locator("#connected-content").waitFor({ state: "visible" });
    await until(async () => (await page.locator(".pool-row").count()) === 2);
    assert.equal(
      await page.locator("#account-status").textContent(),
      "observer · Read only",
    );
    assert.equal(
      await page
        .locator(".pool-row button")
        .filter({ hasText: /^Edit$/ })
        .count(),
      2,
    );
    for (const edit of await page
      .locator(".pool-row button")
      .filter({ hasText: /^Edit$/ })
      .all())
      assert.ok(await edit.isDisabled());
    await page.locator('.nav[data-view="load"]').click();
    for (const load of await page.locator(".load").all())
      assert.ok(await load.isDisabled());
    assert.equal(
      (
        await api(reader, "demo/load", {
          method: "POST",
          data: { pool: "orders", tasks: 4, durationMillis: 400, failEvery: 0 },
        })
      ).status(),
      403,
    );
    checks.push(
      "Named reader sees identity and disabled mutations; direct load write returns 403",
    );
    await page.locator(".app-header .language-picker").selectOption("zh");
    assert.equal(
      await page.locator("#account-status").textContent(),
      "observer · 只读",
    );
    await page.setViewportSize({ width: 390, height: 844 });
    const brand = await page.locator(".brand").boundingBox();
    const picker = await page
      .locator(".app-header .language-picker")
      .boundingBox();
    assert.ok(
      brand.x + brand.width <= picker.x,
      "Header controls overlap at 390px",
    );
    assert.ok(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    );
    await page.setViewportSize({ width: 1440, height: 1050 });
    await page.locator(".app-header .language-picker").selectOption("en");
    assert.match(
      await page.locator("#runtime-status").textContent(),
      /Notifications:.*Configuration sync:/,
    );
    checks.push(
      "Identity, role and delivery/refresh status translate in both languages",
    );
    await page.locator("#disconnect").click();
    await page.locator("#token").fill(writer);
    await page.locator("#login-form button.primary").click();
    await page.locator("#connected-content").waitFor({ state: "visible" });
    await until(
      async () =>
        (await page.locator("#account-status").textContent()) ===
        "oncall · Operator",
    );
    const current = await (await api(writer, "pools/orders")).json();
    assert.equal(
      (
        await api(writer, "pools/orders", {
          method: "PUT",
          data: {
            expectedVersion: current.version,
            reason: "Increase order processing capacity",
            settings: current.settings,
          },
        })
      ).status(),
      200,
    );
    const changes = await (await api(writer, "changes")).json();
    assert.equal(changes[0].actor, "oncall");
    checks.push(
      "Named operator can update configuration and is recorded in audit history",
    );
    assert.equal(
      (
        await api(writer, "demo/load", {
          method: "POST",
          data: { pool: "orders", tasks: 4, durationMillis: 400, failEvery: 0 },
        })
      ).status(),
      202,
    );
    await until(async () => {
      const alerts = await (await api(writer, "alerts")).json();
      return (
        alerts.some((a) => a.kind === "RUN_TIMEOUT") &&
        alerts.some((a) => a.kind === "QUEUE_TIMEOUT")
      );
    });
    await page.locator('.nav[data-view="alerts"]').click();
    await page
      .locator('#alert-query [name="kind"]')
      .selectOption("RUN_TIMEOUT");
    await page.locator("#alert-query button[type=submit]").click();
    await until(async () =>
      /Run timeout/.test(await page.locator("#alert-list").textContent()),
    );
    assert.match(
      await page.locator("#alert-list").textContent(),
      /Run timeout/,
    );
    checks.push(
      "Unfinished queue/run timeouts reach the API and the English alert filter",
    );
    assert.deepEqual(errors, []);
    const resultDir = path.join(root, "test-results");
    fs.mkdirSync(resultDir, { recursive: true });
    fs.writeFileSync(
      path.join(resultDir, "governance-result.json"),
      JSON.stringify({ testedAt: new Date().toISOString(), checks }, null, 2),
    );
    console.log(JSON.stringify({ checks }, null, 2));
  } finally {
    clearTimeout(readyTimer);
    if (browser) await browser.close();
    if (server.exitCode === null) {
      const exited = new Promise((resolve) => server.once("exit", resolve));
      server.kill();
      await Promise.race([
        exited,
        new Promise((resolve) => setTimeout(resolve, 5000)),
      ]);
    }
  }
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
