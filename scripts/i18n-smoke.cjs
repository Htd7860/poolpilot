const { chromium } = require("playwright");
const assert = require("node:assert/strict");
const path = require("node:path");
const fs = require("node:fs");

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
    locale: "en-US",
  });
  const page = await context.newPage();
  const errors = [];
  const checks = [];
  page.on("pageerror", (error) => errors.push(error.message));
  const passed = (message) => checks.push(message);
  const switchLanguage = async (language, dialog) => {
    await page
      .locator((dialog || ".app-header") + " .language-picker")
      .selectOption(language);
    assert.equal(
      await page.locator("html").getAttribute("lang"),
      language === "zh" ? "zh-CN" : "en",
    );
  };
  const connect = async () => {
    await page.locator("#token").fill(token);
    await page.locator("#login-form button.primary[type=submit]").click();
    await page.locator("#connected-content").waitFor({ state: "visible" });
    await page
      .locator('.pool-row[data-pool="orders"]')
      .waitFor({ state: "attached" });
  };
  const noUntranslatedText = async (locator) => {
    const texts = await locator.evaluate((root) => {
      const result = [],
        walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
      for (let node = walker.nextNode(); node; node = walker.nextNode()) {
        if (
          node.parentElement.closest(
            ".language-picker,.user-content,script,style",
          )
        )
          continue;
        if (/[\u4e00-\u9fff]/.test(node.nodeValue))
          result.push(node.nodeValue.trim());
      }
      return result;
    });
    assert.deepEqual(texts, [], "English text contains untranslated Chinese");
  };
  try {
    await page.goto(base + "/poolpilot/index.html");
    assert.equal(await page.title(), "PoolPilot Console");
    await noUntranslatedText(page.locator("#login-dialog"));
    passed("Browser locale selects English on first visit");
    await page.locator("#login-form button.primary[type=submit]").click();
    assert.match(
      await page.locator("#login-error").textContent(),
      /Enter Admin token/,
    );
    await switchLanguage("zh", "#login-dialog");
    assert.match(
      await page.locator("#login-error").textContent(),
      /请填写管理令牌/,
    );
    await switchLanguage("en", "#login-dialog");
    await page.locator("#token").fill("incorrect-token");
    await page.locator("#login-form button.primary[type=submit]").click();
    await page
      .locator("#login-error")
      .filter({ hasText: "Invalid admin token" })
      .waitFor();
    await switchLanguage("zh", "#login-dialog");
    assert.match(await page.locator("#login-error").textContent(), /令牌/);
    assert.equal(await page.locator("#token").inputValue(), "incorrect-token");
    await switchLanguage("en", "#login-dialog");
    passed("Login errors switch language without losing token input");
    await connect();
    await noUntranslatedText(page.locator("#view-pools"));
    assert.equal(await page.locator(".instance-mode,.scope-mode").count(), 0);
    const poolGroup = page.locator('.nav-group[aria-controls="nav-pools"]');
    await poolGroup.click();
    assert.equal(await poolGroup.getAttribute("aria-expanded"), "false");
    assert.equal(await page.locator("#nav-pools").isVisible(), false);
    await poolGroup.press("Enter");
    assert.equal(await page.locator("#nav-pools").isVisible(), true);
    const menuToggle = page.locator("#toggle-sidebar");
    await menuToggle.click();
    assert.equal(await page.locator("#sidebar").isVisible(), false);
    assert.equal(await menuToggle.getAttribute("aria-expanded"), "false");
    assert.equal(
      await page
        .locator("main")
        .evaluate((element) => element.getBoundingClientRect().left),
      0,
    );
    assert.equal(await page.locator("#view-pools").isVisible(), true);
    await switchLanguage("zh");
    assert.equal(await menuToggle.getAttribute("aria-label"), "展开菜单");
    await switchLanguage("en");
    assert.equal(
      await menuToggle.getAttribute("aria-label"),
      "Expand navigation",
    );
    await menuToggle.focus();
    await menuToggle.press("Space");
    assert.equal(await page.locator("#sidebar").isVisible(), true);
    assert.equal(
      await page
        .locator("main")
        .evaluate((element) => element.getBoundingClientRect().left),
      200,
    );
    passed(
      "Navigation and groups collapse with keyboard access; content expands and toggle labels follow language",
    );
    const orders = page.locator('.pool-row[data-pool="orders"]');
    await orders.getByRole("button", { name: "Edit", exact: true }).click();
    const nextCapacity =
      Number(await page.locator("[name=queueCapacity]").inputValue()) + 1;
    await page.locator("[name=queueCapacity]").fill(String(nextCapacity));
    const reason = "订单高峰扩容 / peak-hour capacity";
    await page.locator("[name=reason]").fill(reason);
    await switchLanguage("zh", "#settings-dialog");
    await switchLanguage("en", "#settings-dialog");
    assert.equal(
      await page.locator("[name=queueCapacity]").inputValue(),
      String(nextCapacity),
    );
    assert.equal(await page.locator("[name=reason]").inputValue(), reason);
    assert.match(
      await page.locator("#config-title").textContent(),
      /Edit thread pool: orders/,
    );
    await page
      .locator("[name=reason]")
      .fill("Increase capacity for peak-hour orders");
    await page.locator("[name=coreSize]").fill("9");
    await page.locator("[name=maxSize]").fill("2");
    await page.getByRole("button", { name: "Save and apply" }).click();
    assert.match(
      await page.locator("#config-error").textContent(),
      /Core threads cannot exceed/,
    );
    await switchLanguage("zh", "#settings-dialog");
    assert.match(
      await page.locator("#config-error").textContent(),
      /核心线程数/,
    );
    await switchLanguage("en", "#settings-dialog");
    passed(
      "Settings, user text and client validation survive language changes",
    );
    await page.locator("#refresh-settings").click();
    await page.waitForFunction(
      () => !document.getElementById("refresh-settings").disabled,
    );
    await page
      .locator("[name=reason]")
      .fill("Increase capacity for peak-hour orders");
    await page.locator("[name=queueCapacity]").fill(String(nextCapacity));
    await page.getByRole("button", { name: "Save and apply" }).click();
    await page.locator("#settings-dialog").waitFor({ state: "hidden" });
    await page.locator('.nav[data-view="changes"]').click();
    await page
      .locator("#change-list tr")
      .filter({ hasText: "Increase capacity for peak-hour orders" })
      .first()
      .getByRole("button", { name: "Details" })
      .click();
    await noUntranslatedText(page.locator("#detail-dialog"));
    await switchLanguage("zh", "#detail-dialog");
    await switchLanguage("en", "#detail-dialog");
    assert.equal(
      await page.locator(".diff-table td.changed").last().textContent(),
      String(nextCapacity),
    );
    await page.locator("#dismiss-detail").click();
    passed(
      "English save creates an audit; translated details retain changed values",
    );
    const apiHeaders = { "X-PoolPilot-Token": token };
    const changeResponse = await context.request.get(
      base + "/poolpilot/api/changes",
      { headers: apiHeaders },
    );
    const source = (await changeResponse.json()).find(
      (c) =>
        c.reason === "Increase capacity for peak-hour orders" &&
        c.pool === "orders",
    );
    await page
      .locator("#change-list tr")
      .filter({ hasText: "Increase capacity for peak-hour orders" })
      .first()
      .getByRole("button", { name: "Details" })
      .click();
    await page.locator("#rollback-change").click();
    await page.locator("#rollback-dialog").waitFor({ state: "visible" });
    await noUntranslatedText(page.locator("#rollback-dialog"));
    await page
      .getByRole("button", { name: "Confirm rollback", exact: true })
      .click();
    assert.match(
      await page.locator("#rollback-error").textContent(),
      /Enter Change reason/,
    );
    await page
      .locator("[name=rollbackReason]")
      .fill("Restore the previous queue capacity");
    await switchLanguage("zh", "#rollback-dialog");
    await switchLanguage("en", "#rollback-dialog");
    assert.equal(
      await page.locator("[name=rollbackReason]").inputValue(),
      "Restore the previous queue capacity",
    );
    const beforeExternal = await (
      await context.request.get(base + "/poolpilot/api/pools/orders", {
        headers: apiHeaders,
      })
    ).json();
    const external = await context.request.put(
      base + "/poolpilot/api/pools/orders",
      {
        headers: apiHeaders,
        data: {
          expectedVersion: beforeExternal.version,
          reason: "Adjust capacity for order processing",
          settings: {
            ...beforeExternal.settings,
            queueCapacity: beforeExternal.settings.queueCapacity + 1,
          },
        },
      },
    );
    assert.equal(external.status(), 200);
    await page
      .getByRole("button", { name: "Confirm rollback", exact: true })
      .click();
    await page
      .locator("#rollback-error")
      .filter({ hasText: "Configuration has changed" })
      .waitFor();
    await switchLanguage("zh", "#rollback-dialog");
    assert.match(
      await page.locator("#rollback-error").textContent(),
      /配置已被修改/,
    );
    await switchLanguage("en", "#rollback-dialog");
    passed(
      "Rollback previews target settings, preserves bilingual input and rejects a stale version",
    );
    await page.locator("#refresh-rollback").click();
    await page.waitForFunction(
      () => !document.getElementById("refresh-rollback").disabled,
    );
    const rollbackImages =
      process.env.POOLPILOT_SCREENSHOT_DIR ||
      path.resolve(__dirname, "../docs/images");
    fs.mkdirSync(rollbackImages, { recursive: true });
    await page.screenshot({
      path: path.join(rollbackImages, "rollback-en.png"),
      fullPage: true,
    });
    assert.equal(
      await page.locator("[name=rollbackReason]").inputValue(),
      "Restore the previous queue capacity",
    );
    await page
      .getByRole("button", { name: "Confirm rollback", exact: true })
      .click();
    await page.locator("#rollback-dialog").waitFor({ state: "hidden" });
    const rolledBack = await (
      await context.request.get(base + "/poolpilot/api/pools/orders", {
        headers: apiHeaders,
      })
    ).json();
    assert.deepEqual(rolledBack.settings, source.before);
    assert.equal(rolledBack.version, beforeExternal.version + 2);
    const latest = (
      await (
        await context.request.get(base + "/poolpilot/api/changes", {
          headers: apiHeaders,
        })
      ).json()
    )[0];
    assert.equal(latest.kind, "ROLLBACK");
    assert.equal(latest.sourceChangeId, source.id);
    passed(
      "Reviewed rollback restores the complete target and appends a new version with its source change",
    );
    for (const view of ["pools", "monitors", "alerts", "changes", "load"]) {
      await page.locator('.nav[data-view="' + view + '"]').click();
      await noUntranslatedText(page.locator("#view-" + view));
    }
    await page
      .getByRole("button", { name: "Submit saturation", exact: true })
      .click();
    await page
      .locator("#load-result")
      .filter({ hasText: "accepted" })
      .waitFor();
    await switchLanguage("zh");
    assert.match(await page.locator("#load-result").textContent(), /接收/);
    await switchLanguage("en");
    await page.locator('.nav[data-view="alerts"]').click();
    await noUntranslatedText(page.locator("#view-alerts"));
    assert.equal(
      await page
        .locator("#alert-query [name=pool] option")
        .first()
        .textContent(),
      "All",
    );
    passed(
      "All operational pages, structured alerts and load results are bilingual",
    );
    await page.locator('.nav[data-view="pools"]').click();
    const images =
      process.env.POOLPILOT_SCREENSHOT_DIR ||
      path.resolve(__dirname, "../docs/images");
    fs.mkdirSync(images, { recursive: true });
    await page.screenshot({
      path: path.join(images, "dashboard-en.png"),
      fullPage: true,
    });
    await page.locator('.nav-group[aria-controls="nav-activity"]').click();
    assert.equal(await page.locator("#nav-activity").isVisible(), false);
    await page.setViewportSize({ width: 390, height: 844 });
    assert.equal(
      await page.locator("#nav-activity").evaluate((element) => element.hidden),
      false,
    );
    await page.locator("#toggle-sidebar").click();
    assert.equal(await page.locator("#sidebar").isVisible(), false);
    assert.equal(await page.locator("#toggle-sidebar").isVisible(), true);
    await page.locator("#toggle-sidebar").click();
    assert.equal(
      await page.locator('.nav[data-view="alerts"]').isVisible(),
      true,
    );
    passed(
      "Collapsed desktop groups remain reachable on mobile; the navigation can be hidden and reopened",
    );
    for (const view of ["pools", "monitors", "alerts", "changes", "load"]) {
      await page.locator('.nav[data-view="' + view + '"]').click();
      assert.equal(
        await page.evaluate(
          () => document.documentElement.scrollWidth > innerWidth,
        ),
        false,
        view + " overflows",
      );
    }
    passed(
      "English layout has no horizontal page overflow at 390px on any view",
    );
    await page.reload();
    assert.equal(await page.locator("html").getAttribute("lang"), "en");
    assert.equal(await page.locator("#connected-content").isVisible(), false);
    assert.deepEqual(await page.evaluate(() => Object.keys(localStorage)), [
      "poolpilot.locale",
    ]);
    assert.equal(await page.evaluate(() => sessionStorage.length), 0);
    await connect();
    assert.deepEqual(
      await page.evaluate(() => [...window.PoolPilotI18n.missing]),
      [],
    );
    assert.deepEqual(errors, []);
    passed(
      "Locale persists after reload; token is not stored; no missing translations or JavaScript errors",
    );
    const isolated = await browser.newContext({ locale: "en-US" });
    await isolated.addInitScript(() =>
      localStorage.setItem("poolpilot.locale", "unsupported"),
    );
    const fallback = await isolated.newPage();
    await fallback.goto(base + "/poolpilot/index.html");
    assert.equal(await fallback.locator("html").getAttribute("lang"), "en");
    await isolated.close();
    const blocked = await browser.newContext({ locale: "zh-CN" });
    await blocked.addInitScript(() => {
      Storage.prototype.getItem = () => {
        throw new Error("disabled");
      };
      Storage.prototype.setItem = () => {
        throw new Error("disabled");
      };
    });
    const storagePage = await blocked.newPage();
    await storagePage.goto(base + "/poolpilot/index.html");
    await storagePage
      .locator("#login-dialog .language-picker")
      .selectOption("en");
    assert.equal(await storagePage.title(), "PoolPilot Console");
    await blocked.close();
    passed(
      "Unsupported locale and blocked browser storage have working fallbacks",
    );
    console.log(
      JSON.stringify({ testedAt: new Date().toISOString(), checks }, null, 2),
    );
  } finally {
    await browser.close();
  }
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
