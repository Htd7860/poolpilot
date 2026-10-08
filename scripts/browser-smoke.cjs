// Run against a disposable demo instance: npm ci && npm run test:browser
const { chromium } = require("playwright");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

(async () => {
  const base = process.env.POOLPILOT_URL || "http://127.0.0.1:8080";
  const token = process.env.POOLPILOT_ADMIN_TOKEN;
  assert.ok(token, "Set POOLPILOT_ADMIN_TOKEN for the test instance.");
  const browser = await chromium.launch({
    channel:
      process.env.POOLPILOT_BROWSER === "chromium"
        ? undefined
        : process.env.POOLPILOT_BROWSER || "chrome",
    headless: true,
  });
  const context = await browser.newContext({
    viewport: { width: 1440, height: 1050 },
    reducedMotion: "reduce",
    locale: "zh-CN",
  });
  const page = await context.newPage();
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  const headers = { "X-PoolPilot-Token": token };
  const result = { testedAt: new Date().toISOString(), base, checks: [] };
  const passed = (name) => result.checks.push(name);
  const apiPools = async () =>
    (
      await context.request.get(base + "/poolpilot/api/pools", { headers })
    ).json();
  const until = async (condition, timeout = 12000) => {
    const deadline = Date.now() + timeout;
    while (Date.now() < deadline) {
      if (await condition()) return;
      await new Promise((resolve) => setTimeout(resolve, 150));
    }
    throw new Error("Condition timed out");
  };
  try {
    assert.equal(
      (await context.request.get(base + "/poolpilot/api/pools")).status(),
      401,
    );
    passed("Unauthenticated API is denied");
    await page.goto(base + "/poolpilot/index.html");
    await page.locator("#login-submit:not([disabled])").waitFor();
    assert.deepEqual(
      await (await context.request.get(base + "/poolpilot/api/auth")).json(),
      { mode: "TOKEN" },
    );
    assert.equal(await page.locator("#token-fields").isVisible(), true);
    assert.equal(
      await page.locator("#application-login-hint").isVisible(),
      false,
    );
    assert.equal(
      await page.locator("#login-form button[type=submit]").count(),
      1,
    );
    assert.doesNotMatch(
      await page.locator("#login-dialog").textContent(),
      /Demo token|演示令牌/,
    );
    passed(
      "Token mode shows only token authentication without a default credential hint",
    );
    await page.locator("#token").fill("invalid-token");
    await page.getByRole("button", { name: "连接管理台" }).click();
    await page.locator("#login-error").filter({ hasText: /令牌/ }).waitFor();
    await page.locator("#token").fill(token);
    await page.getByRole("button", { name: "连接管理台" }).click();
    await page.locator("#connected-content").waitFor({ state: "visible" });
    await until(async () => (await page.locator(".pool-row").count()) === 2);
    assert.equal(
      await page.evaluate(
        () =>
          Object.keys(localStorage).filter((key) => key !== "poolpilot.locale")
            .length + sessionStorage.length,
      ),
      0,
    );
    passed(
      "Invalid token is rejected; valid token connects without browser storage",
    );

    const query = page.locator("#pool-query");
    await query.locator("[name=name]").fill("reports");
    await query.getByRole("button", { name: "查询", exact: true }).click();
    assert.equal(await page.locator(".pool-row").count(), 1);
    assert.equal(
      await page.locator(".pool-row").getAttribute("data-pool"),
      "reports",
    );
    await query.locator("[name=name]").fill("missing-pool");
    await query.getByRole("button", { name: "查询", exact: true }).click();
    await page.getByText("没有符合查询条件的线程池", { exact: true }).waitFor();
    await query.getByRole("button", { name: "重置", exact: true }).click();
    await until(async () => (await page.locator(".pool-row").count()) === 2);
    passed("Pool query, empty result and reset work with live data");

    const ordersCard = page.locator(".pool-row").filter({
      has: page.getByRole("button", { name: "orders", exact: true }),
    });
    await ordersCard.getByRole("button", { name: "编辑" }).click();
    await page.locator("[name=queueCapacity]").fill("24");
    await page.locator("[name=reason]").fill("扩容订单队列");
    const save = page.waitForResponse(
      (response) =>
        response.url().endsWith("/api/pools/orders") &&
        response.request().method() === "PUT",
    );
    await page.getByRole("button", { name: "保存并生效" }).click();
    assert.equal((await save).status(), 200);
    await page.locator("#settings-dialog").waitFor({ state: "hidden" });
    let orders = (await apiPools()).find((pool) => pool.name === "orders");
    assert.equal(orders.settings.queueCapacity, 24);
    await page.locator(".nav[data-view=changes]").click();
    await page.getByText("扩容订单队列", { exact: true }).first().waitFor();
    await page
      .locator("#change-list tr")
      .filter({ hasText: "扩容订单队列" })
      .first()
      .getByRole("button", { name: "详情" })
      .click();
    await page.locator("#detail-dialog").waitFor({ state: "visible" });
    assert.equal(
      await page.locator(".diff-table td.changed").last().textContent(),
      "24",
    );
    await page.locator("#dismiss-detail").click();
    passed("UI configuration change is live and audited");

    await page.locator(".nav[data-view=pools]").click();
    await ordersCard.getByRole("button", { name: "编辑" }).click();
    orders = (await apiPools()).find((pool) => pool.name === "orders");
    const external = await context.request.put(
      base + "/poolpilot/api/pools/orders",
      {
        headers,
        data: {
          expectedVersion: orders.version,
          reason: "调整订单队列容量",
          settings: { ...orders.settings, queueCapacity: 25 },
        },
      },
    );
    assert.equal(external.status(), 200);
    await page.locator("[name=reason]").fill("提高订单处理容量");
    const stale = page.waitForResponse(
      (response) =>
        response.url().endsWith("/api/pools/orders") &&
        response.request().method() === "PUT",
    );
    await page.getByRole("button", { name: "保存并生效" }).click();
    assert.equal((await stale).status(), 409);
    await until(async () =>
      (await page.locator("#config-error").textContent()).includes(
        "读取最新配置",
      ),
    );
    await page.getByRole("button", { name: "读取最新配置" }).click();
    await until(
      async () =>
        (await page.locator("[name=queueCapacity]").inputValue()) === "25",
    );
    await page.locator("#close-settings").click();
    passed("Stale UI edit receives 409 and can explicitly refresh");

    const failuresBefore = (await apiPools()).find(
      (pool) => pool.name === "orders",
    ).tasks.failed;
    await page.locator(".nav[data-view=load]").click();
    await page.getByRole("button", { name: "提交部分失败" }).click();
    await until(
      async () =>
        (await apiPools()).find((pool) => pool.name === "orders").tasks
          .failed >=
        failuresBefore + 8,
    );
    passed("Future failures are visible after demo submission");
    const rejectionsBefore = (await apiPools()).find(
      (pool) => pool.name === "orders",
    ).tasks.rejected;
    await page.getByRole("button", { name: "提交队列拥堵" }).click();
    await until(async () => {
      const events = await (
        await context.request.get(base + "/poolpilot/api/alerts", { headers })
      ).json();
      return (
        events.some((event) => event.kind === "QUEUE") &&
        events.some((event) => event.kind === "REJECTION")
      );
    });
    await until(
      async () => Number(await page.locator("#alert-count").textContent()) > 0,
    );
    await page.locator(".nav[data-view=pools]").click();
    await until(
      async () =>
        Number(
          (
            await ordersCard
              .locator("[data-metric=outcomes] .warning")
              .textContent()
          ).replaceAll(",", ""),
        ) > rejectionsBefore,
    );
    passed("Saturation produces queue and rejection alerts");
    const imageDir =
      process.env.POOLPILOT_SCREENSHOT_DIR ||
      path.resolve(__dirname, "../docs/images");
    fs.mkdirSync(imageDir, { recursive: true });
    await page.screenshot({
      path: path.join(imageDir, "dashboard.png"),
      fullPage: true,
    });
    await ordersCard.getByRole("button", { name: "监控", exact: true }).click();
    await page.locator("#view-monitors").waitFor({ state: "visible" });
    assert.equal(await page.locator("#monitor-pool").inputValue(), "orders");
    assert.equal(await page.locator(".monitor-table tbody tr").count(), 6);
    await page.screenshot({
      path: path.join(imageDir, "monitor.png"),
      fullPage: true,
    });
    passed("Pool row opens detailed runtime metrics and real queue trend");
    await page.locator(".nav[data-view=alerts]").click();
    await page
      .locator("#alert-list")
      .getByText("队列压力", { exact: true })
      .first()
      .waitFor();
    await page.locator("#alert-query [name=kind]").selectOption("REJECTION");
    await page
      .locator("#alert-query")
      .getByRole("button", { name: "查询", exact: true })
      .click();
    assert.ok((await page.locator("#alert-list tr").count()) > 0);
    for (const kind of await page
      .locator("#alert-list tr td:nth-child(3)")
      .allTextContents())
      assert.equal(kind, "拒绝处理");
    await page
      .locator("#alert-query")
      .getByRole("button", { name: "重置", exact: true })
      .click();
    passed("Alert type query and reset work with recorded events");
    await page.screenshot({
      path: path.join(imageDir, "alerts.png"),
      fullPage: true,
    });
    await page.locator(".nav[data-view=pools]").click();
    await page.setViewportSize({ width: 390, height: 844 });
    assert.equal(
      await page.evaluate(
        () => document.documentElement.scrollWidth > innerWidth,
      ),
      false,
    );
    await page.screenshot({
      path: path.join(imageDir, "mobile.png"),
      fullPage: true,
    });
    passed("390px mobile layout has no horizontal overflow");
    const metrics = await context.request.get(base + "/actuator/prometheus");
    assert.equal(metrics.status(), 200);
    assert.match(
      await metrics.text(),
      /poolpilot_task_execution_seconds_bucket/,
    );
    passed("Prometheus exports thread-pool histogram");
    // Test-only response fixture; it never changes the running registry or saved screenshots.
    const prototypePool = (await apiPools())[0];
    const fixturePools = Array.from({ length: 21 }, (_, index) => ({
      ...prototypePool,
      name: "fixture-" + String(index + 1).padStart(2, "0"),
    }));
    const fixtureRoute = "**/poolpilot/api/pools";
    await page.route(fixtureRoute, (route) =>
      route.fulfill({ json: fixturePools }),
    );
    try {
      await page
        .locator("#view-pools")
        .getByRole("button", { name: "刷新", exact: true })
        .click();
      await until(
        async () =>
          (await page.locator("#pool-result-count").textContent()) === "21",
      );
      assert.equal(await page.locator(".pool-row").count(), 10);
      await page
        .locator("#pool-pagination")
        .getByRole("button", { name: "下一页" })
        .click();
      assert.equal(
        await page.locator(".pool-row").first().getAttribute("data-pool"),
        "fixture-11",
      );
      await page
        .locator("#pool-pagination")
        .getByRole("button", { name: "下一页" })
        .click();
      assert.equal(await page.locator(".pool-row").count(), 1);
      assert.equal(
        await page.locator(".pool-row").first().getAttribute("data-pool"),
        "fixture-21",
      );
      assert.equal(
        await page
          .locator("#pool-pagination")
          .getByRole("button", { name: "下一页" })
          .isDisabled(),
        true,
      );
      passed(
        "Pagination shows 10/10/1 rows and correct boundaries for a 21-pool test fixture",
      );
    } finally {
      await page.unroute(fixtureRoute);
    }
    await page.locator("#disconnect").click();
    await page.locator("#login-dialog").waitFor({ state: "visible" });
    assert.equal(await page.locator("#connected-content").isVisible(), false);
    assert.deepEqual(errors, []);
    passed("Disconnect hides data; no uncaught JavaScript errors");
    await page.route("**/poolpilot/api/auth", (route) =>
      route.fulfill({ json: { mode: "APPLICATION" } }),
    );
    await page.reload();
    await page.locator("#login-submit:not([disabled])").waitFor();
    assert.equal(await page.locator("#token-fields").isVisible(), false);
    assert.equal(await page.locator("#token").getAttribute("required"), null);
    assert.equal(
      await page.locator("#application-login-hint").isVisible(),
      true,
    );
    assert.equal(
      await page.locator("#login-submit").textContent(),
      "使用当前登录身份",
    );
    await page.locator("#login-submit").click();
    await page
      .locator("#login-error")
      .filter({ hasText: /请先登录业务应用/ })
      .waitFor();
    assert.equal(await page.locator("#connected-content").isVisible(), false);
    await page.locator("#login-dialog .language-picker").selectOption("en");
    assert.equal(
      await page.locator("#login-submit").textContent(),
      "Use current application session",
    );
    assert.match(
      await page.locator("#login-error").textContent(),
      /Sign in to the application/,
    );
    await page.unroute("**/poolpilot/api/auth");
    passed(
      "Application mode shows the session entry; an unauthenticated session is denied in both languages",
    );
    assert.deepEqual(errors, []);
    console.log(JSON.stringify(result, null, 2));
  } finally {
    await browser.close();
  }
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
