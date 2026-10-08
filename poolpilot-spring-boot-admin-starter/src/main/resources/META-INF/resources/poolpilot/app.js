"use strict";
const i18n = window.PoolPilotI18n;
const t = i18n.t;
const $ = (id) => document.getElementById(id);
const views = {
  pools: "线程池列表",
  monitors: "运行监控",
  alerts: "告警记录",
  changes: "配置变更",
  load: "负载测试",
};
const labels = {
  ACTIVITY: "线程繁忙",
  QUEUE: "队列压力",
  REJECTION: "拒绝处理",
  SLOW_TASK: "慢任务",
  QUEUE_TIMEOUT: "排队超时",
  RUN_TIMEOUT: "运行超时",
};
const settingLabels = {
  coreSize: "核心线程数",
  maxSize: "最大线程数",
  queueCapacity: "队列容量",
  keepAliveSeconds: "空闲存活时间（秒）",
  allowCoreTimeout: "核心线程超时",
  rejectionPolicy: "拒绝策略",
  slowTaskMillis: "慢任务阈值（毫秒）",
};
let token = "",
  authenticationMode = null,
  pools = [],
  alerts = [],
  changes = [],
  editing = null,
  rollbackState = null,
  instanceInfo = null,
  timer = null,
  session = 0,
  inflight = null,
  currentView = "pools";
const history = new Map();
const canWrite = () => instanceInfo?.writable === true;
const filters = {
  pool: { name: "", policy: "" },
  alert: { pool: "", kind: "" },
  change: { pool: "" },
};
const pages = { pool: 1, alert: 1, change: 1 };
const mobileNavigation = window.matchMedia("(max-width: 700px)");
const navigationGroups = new Map(
  [...document.querySelectorAll(".nav-group")].map((button) => [
    button.getAttribute("aria-controls"),
    true,
  ]),
);
let sidebarExpanded = true;
function renderNavigation() {
  $("sidebar").hidden = !sidebarExpanded;
  document.body.classList.toggle("sidebar-collapsed", !sidebarExpanded);
  const toggle = $("toggle-sidebar");
  const label = t(sidebarExpanded ? "收起菜单" : "展开菜单");
  toggle.setAttribute("aria-expanded", String(sidebarExpanded));
  toggle.setAttribute("aria-label", label);
  toggle.title = label;
  for (const button of document.querySelectorAll(".nav-group")) {
    const id = button.getAttribute("aria-controls");
    const expanded = mobileNavigation.matches || navigationGroups.get(id);
    button.setAttribute("aria-expanded", String(expanded));
    $(id).hidden = !expanded;
  }
}
$("toggle-sidebar").onclick = () => {
  sidebarExpanded = !sidebarExpanded;
  renderNavigation();
};
document.querySelectorAll(".nav-group").forEach((button) => {
  button.onclick = () => {
    const id = button.getAttribute("aria-controls");
    navigationGroups.set(id, !navigationGroups.get(id));
    renderNavigation();
  };
});
mobileNavigation.addEventListener("change", renderNavigation);
const pageSize = 10;
const node = (tag, cls, text) => {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text !== undefined) n.textContent = text;
  return n;
};
const number = (n) => Number(n).toLocaleString(i18n.locale());
const ms = (n) => (Number(n) || 0).toFixed(1);
const timestamp = (v) =>
  new Date(v).toLocaleString(i18n.locale(), { hour12: false });
const cell = (row, text, cls) => {
  const td = node("td", cls, text);
  row.append(td);
  return td;
};
const action = (text, handler) => {
  const button = node("button", "link-button", text);
  button.type = "button";
  button.onclick = handler;
  return button;
};
const messages = new Map();
let lastSampleAt = null,
  connectionState = "未连接",
  detailChange = null,
  loadResult = null;
function message(id, source) {
  messages.set(id, typeof source === "function" ? source : () => source);
  $(id).textContent = messages.get(id)();
}
function notice(message, error = false) {
  $("notice").hidden = !(typeof message === "function" ? message() : message);
  messages.set(
    "notice",
    typeof message === "function" ? message : () => message,
  );
  $("notice").textContent = messages.get("notice")();
  $("notice").classList.toggle("error", error);
}
async function api(path, options = {}) {
  const response = await fetch("api/" + path, {
    ...options,
    headers: {
      "X-PoolPilot-Token": token,
      "Content-Type": "application/json",
      "Accept-Language": i18n.locale(),
      ...(options.headers || {}),
    },
    cache: "no-store",
    signal: AbortSignal.timeout(10000),
  }).catch((error) => {
    if (error.name === "TimeoutError") throw error;
    throw clientError("无法连接服务，请确认应用仍在运行");
  });
  let body;
  try {
    body = await response.json();
  } catch {
    throw clientError("无法读取服务响应，请确认应用仍在运行");
  }
  if (!response.ok) {
    const error = new Error(body.error || body.message || t("请求失败"));
    error.status = response.status;
    error.code = body.code;
    error.parameters = body.parameters || {};
    Object.defineProperty(error, "message", { get: () => errorMessage(body) });
    throw error;
  }
  return body;
}
function showView(view, updateHash = true) {
  currentView = Object.hasOwn(views, view) ? view : "pools";
  for (const key of Object.keys(views))
    $("view-" + key).hidden = key !== currentView;
  document.querySelectorAll(".nav").forEach((button) => {
    const active = button.dataset.view === currentView;
    button.classList.toggle("active", active);
    if (active) button.setAttribute("aria-current", "page");
    else button.removeAttribute("aria-current");
  });
  const parent = document
    .querySelector('.nav[data-view="' + currentView + '"]')
    .closest(".nav-children");
  navigationGroups.set(parent.id, true);
  renderNavigation();
  $("view-title").textContent = t(views[currentView]);
  notice("");
  if (updateHash && location.hash !== "#" + currentView)
    location.hash = currentView;
  if (currentView === "monitors") renderMonitor();
}
function disconnect() {
  session++;
  token = "";
  clearTimeout(timer);
  inflight = null;
  pools = [];
  alerts = [];
  changes = [];
  history.clear();
  editing = null;
  rollbackState = null;
  instanceInfo = null;
  renderStorage();
  $("connected-content").hidden = true;
  $("disconnect").hidden = true;
  $("connection-dot").className = "dot";
  connectionState = "未连接";
  lastSampleAt = null;
  $("connection-text").textContent = t(connectionState);
  $("sample-time").textContent = t("未采样");
  $("pool-count").textContent = "0";
  $("alert-count").textContent = "0";
  for (const id of ["settings-dialog", "detail-dialog", "rollback-dialog"])
    if ($(id).open) $(id).close();
  $("load-result").hidden = true;
  loadResult = null;
  detailChange = null;
  if (!$("login-dialog").open) $("login-dialog").showModal();
}
function paginate(key, items, render) {
  const totalPages = Math.max(1, Math.ceil(items.length / pageSize));
  pages[key] = Math.min(pages[key], totalPages);
  const target = $(key + "-pagination");
  target.replaceChildren(
    node(
      "span",
      "count",
      t("共 {count} 条 / 每页 {size} 条", {
        count: items.length,
        size: pageSize,
      }),
    ),
  );
  const button = (text, page, disabled = false) => {
    const b = node("button", "page-button", text);
    b.type = "button";
    b.disabled = disabled;
    b.onclick = () => {
      pages[key] = page;
      render();
    };
    return b;
  };
  target.append(button(t("上一页"), pages[key] - 1, pages[key] === 1));
  for (let page = 1; page <= totalPages; page++) {
    if (
      totalPages > 5 &&
      page !== 1 &&
      page !== totalPages &&
      Math.abs(page - pages[key]) > 1
    )
      continue;
    const b = button(String(page), page);
    b.setAttribute("aria-label", t("第 {page} 页", { page }));
    if (page === pages[key]) b.setAttribute("aria-current", "page");
    target.append(b);
  }
  target.append(button(t("下一页"), pages[key] + 1, pages[key] === totalPages));
  return items.slice((pages[key] - 1) * pageSize, pages[key] * pageSize);
}
function empty(target, columns, message) {
  const row = node("tr");
  const td = cell(row, message, "empty-cell");
  td.colSpan = columns;
  target.append(row);
}
function syncSelect(id, all = false) {
  const select = $(id),
    previous = select.value;
  const names = pools.map((p) => p.name),
    existing = [...select.options].map((o) => o.value);
  const desired = all ? ["", ...names] : names;
  if (
    existing.length === desired.length &&
    existing.every((name, i) => name === desired[i])
  ) {
    if (all) select.options[0].textContent = t("全部");
    return;
  }
  select.replaceChildren(
    ...desired.map((name) => {
      const option = node("option", "", name || t("全部"));
      option.value = name;
      return option;
    }),
  );
  if (desired.includes(previous)) select.value = previous;
}
function renderPools() {
  const target = $("pool-list"),
    f = filters.pool;
  target.replaceChildren();
  const selected = pools.filter(
    (p) =>
      p.name.toLowerCase().includes(f.name.toLowerCase()) &&
      (!f.policy || p.settings.rejectionPolicy === f.policy),
  );
  $("pool-result-count").textContent = selected.length;
  const visible = paginate("pool", selected, renderPools);
  for (const p of visible) {
    const row = node("tr", "pool-row");
    row.dataset.pool = p.name;
    const name = cell(row);
    const nameButton = action(p.name, () => openMonitor(p.name));
    nameButton.classList.add("pool-name");
    name.append(
      nameButton,
      node(
        "span",
        "pool-meta",
        p.settings.rejectionPolicy + " · v" + p.version,
      ),
    );
    const pressure =
      p.activeCount / Math.max(1, p.settings.maxSize) >= 0.9 ||
      p.queueSize / p.settings.queueCapacity >= 0.8;
    const state = cell(row);
    state.append(
      node(
        "span",
        "state" + (p.shutdown ? " stopped" : pressure ? " pressure" : ""),
        p.shutdown ? t("已关闭") : pressure ? t("繁忙") : t("运行中"),
      ),
    );
    cell(row, p.settings.coreSize + " / " + p.settings.maxSize);
    cell(row, p.activeCount + " / " + p.poolSize);
    const queue = cell(row);
    const queueContent = node("div", "queue-cell"),
      track = node("span", "queue-track"),
      fill = node(
        "span",
        "queue-fill" +
          (p.queueSize / p.settings.queueCapacity >= 0.8 ? " pressure" : ""),
      );
    fill.style.width =
      Math.min(100, (p.queueSize / p.settings.queueCapacity) * 100) + "%";
    track.append(fill);
    queueContent.append(
      node("span", "", p.queueSize + " / " + p.settings.queueCapacity),
      track,
    );
    queue.append(queueContent);
    cell(row, number(p.tasks.completed));
    const counts = cell(row);
    counts.append(
      node("span", p.tasks.failed ? "danger" : "", number(p.tasks.failed)),
      node("span", "", " / "),
      node("span", p.tasks.rejected ? "warning" : "", number(p.tasks.rejected)),
    );
    counts.dataset.metric = "outcomes";
    cell(
      row,
      ms(p.tasks.waitP95Millis) +
        " / " +
        ms(p.tasks.executionP95Millis) +
        " ms",
    );
    const actions = cell(row),
      group = node("div", "row-actions"),
      edit = action(t("编辑"), () => openSettings(p.name));
    edit.disabled = p.shutdown || !canWrite();
    group.append(
      edit,
      action(t("监控"), () => openMonitor(p.name)),
    );
    actions.append(group);
    target.append(row);
  }
  if (!visible.length)
    empty(
      target,
      9,
      pools.length
        ? t("没有符合查询条件的线程池")
        : t("尚未配置线程池，请在启动配置中添加线程池。"),
    );
  $("pool-count").textContent = pools.length;
}
function renderAlerts() {
  const target = $("alert-list"),
    f = filters.alert;
  target.replaceChildren();
  const selected = alerts.filter(
    (a) => (!f.pool || a.pool === f.pool) && (!f.kind || a.kind === f.kind),
  );
  $("alert-result-count").textContent = selected.length;
  for (const a of paginate("alert", selected, renderAlerts)) {
    const row = node("tr");
    cell(row, timestamp(a.timestamp));
    cell(row, a.pool);
    cell(row, t(labels[a.kind] || a.kind), "warning");
    cell(row, alertMessage(a));
    cell(row, "v" + a.configVersion);
    target.append(row);
  }
  if (!selected.length)
    empty(
      target,
      5,
      alerts.length ? t("没有符合查询条件的告警") : t("暂无告警记录"),
    );
  $("alert-count").textContent = alerts.length;
}
function renderChanges() {
  const target = $("change-list");
  target.replaceChildren();
  const selected = changes.filter((c) =>
    c.pool.toLowerCase().includes(filters.change.pool.toLowerCase()),
  );
  $("change-result-count").textContent = selected.length;
  for (const c of paginate("change", selected, renderChanges)) {
    const row = node("tr");
    cell(row, timestamp(c.timestamp));
    cell(row, c.pool);
    cell(row, "v" + c.previousVersion + " → v" + c.version);
    cell(row, c.actor);
    cell(row, c.reason, "user-content");
    cell(row).append(action(t("详情"), () => openChange(c)));
    target.append(row);
  }
  if (!selected.length)
    empty(
      target,
      6,
      changes.length ? t("没有符合查询条件的变更") : t("暂无配置变更记录"),
    );
}
function metricTable(headers, rows, cls) {
  const wrap = node("div", "table-wrap");
  wrap.tabIndex = 0;
  const table = node("table", "data-table " + cls),
    head = node("thead"),
    heading = node("tr"),
    body = node("tbody");
  for (const text of headers) {
    const th = node("th", "", text);
    th.scope = "col";
    heading.append(th);
  }
  head.append(heading);
  for (const values of rows) {
    const row = node("tr");
    for (const value of values) cell(row, value);
    body.append(row);
  }
  table.append(head, body);
  wrap.append(table);
  return wrap;
}
function chart(samples) {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("viewBox", "0 0 900 130");
  svg.setAttribute("preserveAspectRatio", "none");
  svg.setAttribute("role", "img");
  svg.setAttribute(
    "aria-label",
    t("最近 {count} 次采样的队列长度趋势", { count: samples.length }),
  );
  const max = Math.max(1, ...samples.map((s) => s.queue));
  for (let i = 0; i <= 3; i++) {
    const line = document.createElementNS(svg.namespaceURI, "line");
    line.setAttribute("x1", "0");
    line.setAttribute("x2", "900");
    line.setAttribute("y1", String(i * 35 + 8));
    line.setAttribute("y2", String(i * 35 + 8));
    line.setAttribute("stroke", "#eee");
    svg.append(line);
  }
  const line = document.createElementNS(svg.namespaceURI, "polyline");
  line.setAttribute(
    "points",
    samples
      .map(
        (s, i) =>
          (i / Math.max(1, samples.length - 1)) * 898 +
          1 +
          "," +
          (113 - (s.queue / max) * 100),
      )
      .join(" "),
  );
  line.setAttribute("fill", "none");
  line.setAttribute("stroke", "#0875c8");
  line.setAttribute("stroke-width", "2");
  line.setAttribute("vector-effect", "non-scaling-stroke");
  svg.append(line);
  return svg;
}
function renderMonitor() {
  const target = $("monitor-content");
  target.replaceChildren();
  const p = pools.find((p) => p.name === $("monitor-pool").value);
  if (!p) {
    target.append(node("p", "table-note", t("尚未配置线程池")));
    return;
  }
  const overview = node("div", "monitor-overview");
  for (const [label, value] of [
    [t("活跃 / 最大线程"), p.activeCount + " / " + p.settings.maxSize],
    [t("队列占用 / 容量"), p.queueSize + " / " + p.settings.queueCapacity],
    [t("已完成任务"), number(p.tasks.completed)],
    [t("拒绝事件"), number(p.tasks.rejected)],
  ]) {
    const item = node("div", "monitor-stat");
    item.append(node("span", "", label), node("strong", "", value));
    overview.append(item);
  }
  target.append(overview);
  const heading = node("div", "subheading");
  heading.append(
    node("h2", "", t("任务统计")),
    node(
      "span",
      "muted",
      t("配置 v{version} · {policy}", {
        version: p.version,
        policy: p.settings.rejectionPolicy,
      }),
    ),
  );
  target.append(heading);
  target.append(
    metricTable(
      [t("累计指标"), t("计数"), t("耗时 / 样本"), t("数值")],
      [
        [
          t("提交尝试"),
          number(p.tasks.submitted),
          t("等待 P95"),
          ms(p.tasks.waitP95Millis) + " ms",
        ],
        [
          t("完成任务"),
          number(p.tasks.completed),
          t("执行 P95"),
          ms(p.tasks.executionP95Millis) + " ms",
        ],
        [
          t("失败任务"),
          number(p.tasks.failed),
          t("执行 P99"),
          ms(p.tasks.executionP99Millis) + " ms",
        ],
        [
          t("取消任务"),
          number(p.tasks.cancelled),
          t("平均等待"),
          ms(p.tasks.averageWaitMillis) + " ms",
        ],
        [
          t("慢任务"),
          number(p.tasks.slowTasks),
          t("平均执行"),
          ms(p.tasks.averageExecutionMillis) + " ms",
        ],
        [
          t("调用方执行"),
          number(p.tasks.callerRuns),
          t("窗口样本数"),
          number(p.tasks.windowSamples),
        ],
      ],
      "monitor-table",
    ),
  );
  const samples = history.get(p.name) || [],
    trend = node("div", "trend-panel");
  trend.append(node("h3", "", t("队列长度趋势")), chart(samples));
  const times = node("div", "trend-labels");
  times.append(
    node(
      "span",
      "",
      samples.length
        ? new Date(samples[0].at).toLocaleTimeString(i18n.locale())
        : "",
    ),
    node(
      "span",
      "",
      t("最近 {count} 次采样 · 最高 {max}", {
        count: samples.length,
        max: Math.max(0, ...samples.map((s) => s.queue)),
      }),
    ),
  );
  trend.append(times);
  target.append(trend);
}
function openMonitor(name) {
  $("monitor-pool").value = name;
  showView("monitors");
}
function openChange(c) {
  detailChange = c;
  $("detail-title").textContent = t("配置变更详情");
  const target = $("detail-content");
  target.replaceChildren();
  const meta = node("dl", "detail-meta");
  for (const [label, value] of [
    [t("线程池名称"), c.pool],
    [t("变更时间"), timestamp(c.timestamp)],
    [t("操作人"), c.actor],
    [
      t("操作类型"),
      t(
        c.kind === "ROLLBACK"
          ? "回滚"
          : c.kind === "EXTERNAL"
            ? "配置同步"
            : "调参",
      ),
    ],
    ...(c.sourceChangeId ? [[t("来源变更"), "#" + c.sourceChangeId]] : []),
    [t("配置版本"), "v" + c.previousVersion + " → v" + c.version],
    [t("变更原因"), c.reason],
  ])
    meta.append(node("dt", "", label), node("dd", "", value));
  const rows = Object.keys(settingLabels).map((key) => [
    t(settingLabels[key]),
    settingValue(c.before[key]),
    settingValue(c.after[key]),
  ]);
  const table = metricTable(
    [t("参数"), t("变更前"), t("变更后")],
    rows,
    "diff-table",
  );
  [...table.querySelectorAll("tbody tr")].forEach((row, i) => {
    const key = Object.keys(settingLabels)[i];
    if (c.before[key] !== c.after[key]) row.lastChild.classList.add("changed");
  });
  target.append(meta, table);
  $("rollback-change").disabled =
    !canWrite() || !pools.some((p) => p.name === c.pool && !p.shutdown);
  if (!$("detail-dialog").open) $("detail-dialog").showModal();
}
function renderStorage() {
  $("account-status").hidden = !instanceInfo;
  $("account-status").textContent = instanceInfo
    ? t("{user} · {role}", {
        user: instanceInfo.user,
        role: t(canWrite() ? "操作员" : "只读"),
      })
    : "";
  $("account-status").title = $("account-status").textContent;
  document
    .querySelectorAll(".load")
    .forEach((button) => (button.disabled = !canWrite()));
  const delivery = instanceInfo?.notifications,
    source = instanceInfo?.configSources;
  $("runtime-status").hidden = !instanceInfo;
  $("runtime-status").textContent =
    delivery && source
      ? t(
          "通知：待处理 {queued}，丢弃 {dropped}，失败 {failed} · 配置同步：已应用 {applied}，冲突 {conflicts}，失败 {failures}",
          {
            ...delivery,
            ...source,
            failed: delivery.failed,
            failures: source.failures,
          },
        )
      : "";
  $("storage-status").textContent = t(
    !instanceInfo
      ? "连接后查看配置保存方式。"
      : instanceInfo.configurationPersistence
        ? "配置与变更记录已启用本地持久化。仅管理当前应用实例。"
        : "配置与变更记录保存在内存，重启后恢复启动配置。",
  );
}
function renderRollback() {
  if (!rollbackState) return;
  const { change, current } = rollbackState;
  $("rollback-title").textContent = t("回滚线程池：{name}", {
    name: change.pool,
  });
  $("rollback-version").textContent = t(
    "当前版本 v{version} · 恢复变更 #{id} 之前的配置",
    { version: current.version, id: change.id },
  );
  const keys = Object.keys(settingLabels);
  const table = metricTable(
    [t("参数"), t("当前配置"), t("回滚目标")],
    keys.map((key) => [
      t(settingLabels[key]),
      settingValue(current.settings[key]),
      settingValue(change.before[key]),
    ]),
    "diff-table",
  );
  [...table.querySelectorAll("tbody tr")].forEach((row, i) => {
    if (current.settings[keys[i]] !== change.before[keys[i]])
      row.lastChild.classList.add("changed");
  });
  $("rollback-preview").replaceChildren(table);
}
$("rollback-change").onclick = async () => {
  if (!canWrite()) return;
  const change = detailChange,
    generation = session,
    button = $("rollback-change");
  button.disabled = true;
  try {
    const current = await api("pools/" + encodeURIComponent(change.pool));
    if (
      generation !== session ||
      change !== detailChange ||
      !$("detail-dialog").open
    )
      return;
    rollbackState = { change, current };
    $("rollback-form").elements.rollbackReason.value = "";
    message("rollback-error", () => "");
    renderRollback();
    $("detail-dialog").close();
    $("rollback-dialog").showModal();
  } catch (error) {
    if (generation === session) notice(() => error.message, true);
  } finally {
    button.disabled = false;
  }
};
$("close-rollback").onclick = () => $("rollback-dialog").close();
$("rollback-dialog").addEventListener("close", () => {
  rollbackState = null;
});
$("refresh-rollback").onclick = async () => {
  const state = rollbackState,
    generation = session,
    button = $("refresh-rollback");
  if (!state) return;
  button.disabled = true;
  try {
    const current = await api("pools/" + encodeURIComponent(state.change.pool));
    if (generation !== session || state !== rollbackState) return;
    state.current = current;
    renderRollback();
    message("rollback-error", () => "");
  } catch (error) {
    if (generation === session && state === rollbackState)
      message("rollback-error", () => error.message);
  } finally {
    button.disabled = false;
  }
};
$("rollback-form").onsubmit = async (event) => {
  event.preventDefault();
  if (!canWrite()) return;
  const state = rollbackState,
    generation = session;
  if (!state || !validateForm(event.target, "rollback-error")) return;
  const button = event.submitter;
  button.disabled = true;
  try {
    await api("pools/" + encodeURIComponent(state.change.pool) + "/rollback", {
      method: "POST",
      body: JSON.stringify({
        changeId: state.change.id,
        expectedVersion: state.current.version,
        reason: event.target.elements.rollbackReason.value,
      }),
    });
    if (generation !== session || state !== rollbackState) return;
    $("rollback-dialog").close();
    notice(() => t("配置已回滚，已生成新版本和变更记录"));
    await sample();
  } catch (error) {
    if (generation === session && state === rollbackState)
      message("rollback-error", () => error.message);
  } finally {
    button.disabled = false;
  }
};
async function sample() {
  if (!instanceInfo) return;
  if (inflight) return inflight.promise;
  const generation = session;
  clearTimeout(timer);
  const promise = (async () => {
    try {
      const result = await Promise.all([
        api("pools"),
        api("alerts"),
        api("changes"),
        api("info"),
      ]);
      if (generation !== session) return;
      [pools, alerts, changes, instanceInfo] = result;
      renderStorage();
      const at = Date.now();
      for (const p of pools) {
        const samples = history.get(p.name) || [];
        samples.push({ queue: p.queueSize, at });
        if (samples.length > 30) samples.shift();
        history.set(p.name, samples);
      }
      for (const id of ["monitor-pool", "load-pool"]) syncSelect(id);
      syncSelect("alert-pool", true);
      renderPools();
      renderAlerts();
      renderChanges();
      if (currentView === "monitors") renderMonitor();
      $("sample-time").textContent = t("更新于 {time}", {
        time: new Date(at).toLocaleTimeString(i18n.locale()),
      });
      $("connection-dot").className = "dot online";
      connectionState = "已连接";
      lastSampleAt = at;
      $("connection-text").textContent = t(connectionState);
    } catch (error) {
      if (generation !== session) return;
      if (error.status === 401) {
        message("login-error", () => error.message);
        disconnect();
      } else {
        $("connection-dot").className = "dot error";
        connectionState = "连接异常";
        $("connection-text").textContent = t(connectionState);
        notice(
          () =>
            error.name === "TimeoutError"
              ? t("请求超时，请确认服务状态")
              : error.message,
          true,
        );
      }
    } finally {
      if (generation === session) {
        inflight = null;
        if (instanceInfo) timer = setTimeout(sample, 2000);
      }
    }
  })();
  inflight = { generation, promise };
  return promise;
}
function openSettings(name) {
  if (!canWrite()) return;
  const p = pools.find((p) => p.name === name);
  if (!p) return;
  editing = { name, version: p.version };
  $("config-title").textContent = t("编辑线程池：{name}", { name });
  $("config-version").textContent = t(
    "当前版本 v{version}；保存时校验配置版本",
    { version: p.version },
  );
  const form = $("settings-form");
  for (const [key, value] of Object.entries(p.settings)) {
    const input = form.elements.namedItem(key);
    if (input) {
      if (input.type === "checkbox") input.checked = value;
      else input.value = value;
    }
  }
  form.elements.reason.value = "";
  message("config-error", () => "");
  if (!$("settings-dialog").open) $("settings-dialog").showModal();
}
$("login-form").onsubmit = async (event) => {
  event.preventDefault();
  if (authenticationMode === null) {
    await loadAuthentication();
    return;
  }
  const applicationLogin = authenticationMode === "APPLICATION";
  if (!applicationLogin && !validateForm(event.target, "login-error")) return;
  const generation = ++session;
  token = applicationLogin ? "" : $("token").value.trim();
  clearTimeout(timer);
  inflight = null;
  const button = event.submitter;
  button.disabled = true;
  message("login-error", () => "");
  try {
    const [, info] = await Promise.all([api("pools"), api("info")]);
    if (generation !== session) return;
    instanceInfo = info;
    renderStorage();
    $("login-dialog").close();
    $("token").value = "";
    $("connected-content").hidden = false;
    $("disconnect").hidden = false;
    notice("");
    await sample();
  } catch (error) {
    if (generation === session) {
      token = "";
      message("login-error", () =>
        error.name === "TimeoutError"
          ? t("连接超时，请确认应用已启动")
          : applicationLogin && error.status === 401
            ? t("请先登录业务应用，并确认具有线程池管理权限。")
            : error.message,
      );
    }
  } finally {
    button.disabled = false;
  }
};
function renderAuthentication() {
  const applicationLogin = authenticationMode === "APPLICATION";
  $("token-fields").hidden = authenticationMode !== "TOKEN";
  $("token").required = authenticationMode === "TOKEN";
  $("application-login-hint").hidden = !applicationLogin;
  $("login-submit").textContent = t(
    applicationLogin
      ? "使用当前登录身份"
      : authenticationMode === null
        ? "重试连接"
        : "连接管理台",
  );
}
async function loadAuthentication() {
  $("login-submit").disabled = true;
  try {
    const info = await api("auth");
    if (!["TOKEN", "APPLICATION"].includes(info.mode))
      throw clientError("无法读取认证方式，请确认管理接口已启用。");
    authenticationMode = info.mode;
    message("login-error", () => "");
  } catch {
    authenticationMode = null;
    message("login-error", () => t("无法读取认证方式，请确认管理接口已启用。"));
  } finally {
    renderAuthentication();
    $("login-submit").disabled = false;
  }
}
$("login-dialog").addEventListener("cancel", (event) => event.preventDefault());
$("disconnect").onclick = disconnect;
document
  .querySelectorAll(".nav")
  .forEach((button) => (button.onclick = () => showView(button.dataset.view)));
window.addEventListener("hashchange", () =>
  showView(location.hash.slice(1), false),
);
$("monitor-pool").onchange = renderMonitor;
$("load-monitor").onclick = () => openMonitor($("load-pool").value);
document.querySelectorAll(".refresh-data").forEach(
  (button) =>
    (button.onclick = async () => {
      button.disabled = true;
      try {
        await sample();
      } finally {
        button.disabled = false;
      }
    }),
);
function bindQuery(id, key, render) {
  const form = $(id);
  const apply = () => {
    for (const field of Object.keys(filters[key]))
      filters[key][field] = form.elements.namedItem(field).value.trim();
    pages[key] = 1;
    render();
  };
  form.onsubmit = (event) => {
    event.preventDefault();
    apply();
  };
  form.onreset = (event) => {
    event.preventDefault();
    for (const field of Object.keys(filters[key]))
      form.elements.namedItem(field).value = "";
    apply();
  };
}
bindQuery("pool-query", "pool", renderPools);
bindQuery("alert-query", "alert", renderAlerts);
bindQuery("change-query", "change", renderChanges);
$("close-settings").onclick = () => $("settings-dialog").close();
$("close-detail").onclick = $("dismiss-detail").onclick = () =>
  $("detail-dialog").close();
$("refresh-settings").onclick = async () => {
  const button = $("refresh-settings");
  const edit = editing,
    generation = session;
  button.disabled = true;
  try {
    const latest = await api("pools/" + encodeURIComponent(edit.name));
    if (generation !== session || edit !== editing) return;
    pools = pools.map((p) => (p.name === latest.name ? latest : p));
    openSettings(latest.name);
    renderPools();
  } catch (error) {
    if (generation === session && edit === editing)
      message("config-error", () => error.message);
  } finally {
    button.disabled = false;
  }
};
$("settings-form").onsubmit = async (event) => {
  event.preventDefault();
  if (!canWrite()) return;
  if (!editing) return;
  if (!validateForm(event.target, "config-error")) return;
  const edit = editing,
    generation = session;
  const form = event.target,
    settings = {};
  for (const key of [
    "coreSize",
    "maxSize",
    "queueCapacity",
    "keepAliveSeconds",
    "slowTaskMillis",
  ])
    settings[key] = Number(form.elements.namedItem(key).value);
  settings.allowCoreTimeout = form.elements.allowCoreTimeout.checked;
  settings.rejectionPolicy = form.elements.rejectionPolicy.value;
  if (settings.coreSize > settings.maxSize) {
    message("config-error", () => t("核心线程数不能大于最大线程数"));
    return;
  }
  const button = event.submitter;
  button.disabled = true;
  try {
    await api("pools/" + encodeURIComponent(edit.name), {
      method: "PUT",
      body: JSON.stringify({
        expectedVersion: edit.version,
        reason: form.elements.reason.value,
        settings,
      }),
    });
    if (generation !== session || edit !== editing) return;
    $("settings-dialog").close();
    notice(() => t("配置已保存，当前实例已生效"));
    await sample();
  } catch (error) {
    if (generation !== session || edit !== editing) return;
    message(
      "config-error",
      () =>
        error.message +
        (error.status === 409 ? t("；点击“读取最新配置”后重新填写。") : ""),
    );
  } finally {
    button.disabled = false;
  }
};
const profiles = {
  steady: { tasks: 12, durationMillis: 120, failEvery: 0 },
  burst: { tasks: 100, durationMillis: 800, failEvery: 0 },
  failure: { tasks: 24, durationMillis: 200, failEvery: 3 },
  slow: { tasks: 16, durationMillis: 1100, failEvery: 0 },
};
document.querySelectorAll(".load").forEach(
  (button) =>
    (button.onclick = async () => {
      if (!canWrite()) return;
      const generation = session,
        pool = $("load-pool").value;
      document.querySelectorAll(".load").forEach((b) => (b.disabled = true));
      try {
        const result = await api("demo/load", {
          method: "POST",
          body: JSON.stringify({ pool, ...profiles[button.dataset.profile] }),
        });
        if (generation !== session) return;
        loadResult = { pool, ...result };
        renderLoadResult();
        await sample();
      } catch (error) {
        if (generation === session)
          notice(
            () =>
              error.status === 404
                ? t("当前应用未提供演示负载接口。")
                : error.message,
            true,
          );
      } finally {
        document
          .querySelectorAll(".load")
          .forEach((b) => (b.disabled = !canWrite()));
      }
    }),
);
const errorKeys = {
  UNAUTHORIZED: "管理令牌不正确，请重新连接",
  FORBIDDEN: "当前账号只有读取权限",
  VERSION_CONFLICT: "配置已被修改，请读取最新配置后重试。当前版本：{version}",
  POOL_NOT_FOUND: "找不到指定线程池",
  CHANGE_REQUIRED: "changeId 必须为正整数",
  CHANGE_NOT_FOUND: "该线程池没有此变更记录，记录可能已过期",
  PERSISTENCE_FAILED: "配置保存失败，本次变更未生效，请检查服务端存储",
  POOL_CLOSED: "线程池已关闭，无法修改配置",
  SETTINGS_REQUIRED: "必须提供全部配置参数，且不能为 null",
  VERSION_REQUIRED: "expectedVersion 必须为正整数",
  REASON_REQUIRED: "请填写 1 到 200 字的变更原因",
  THREAD_RANGE:
    "线程数必须满足 0 ≤ coreSize ≤ maxSize ≤ 1024，maxSize 至少为 1",
  QUEUE_RANGE: "queueCapacity 必须在 1 到 1000000 之间",
  KEEP_ALIVE_RANGE:
    "keepAliveSeconds 必须在 0 到 86400 之间；核心线程超时时必须大于 0",
  SLOW_TASK_RANGE: "slowTaskMillis 必须在 1 到 86400000 之间",
  POOL_REQUIRED: "必须提供线程池名称",
  LOAD_RANGE: "任务数 1–200，耗时 1–2000 毫秒，失败间隔 0–200",
  LOAD_POLICY: "演示负载只支持 ABORT 策略；业务接入可使用 CALLER_RUNS",
  LOAD_BUSY: "正在提交演示任务，请稍后重试",
  INVALID_REQUEST: "请求参数无效，请检查 JSON 和配置取值",
};

function settingValue(value) {
  return typeof value === "boolean" ? t(value ? "是" : "否") : String(value);
}
function clientError(key) {
  const error = new Error(t(key));
  Object.defineProperty(error, "message", { get: () => t(key) });
  return error;
}
function validateForm(form, errorId) {
  for (const input of form.querySelectorAll("input,textarea,select")) {
    if (!input.required || (input.validity.valid && input.value.trim()))
      continue;
    const key =
      input.id === "token"
        ? "管理令牌"
        : ["reason", "rollbackReason"].includes(input.name)
          ? "变更原因"
          : settingLabels[input.name];
    const label = () => t(key || input.name);
    message(errorId, () =>
      input.validity.rangeUnderflow ||
      input.validity.rangeOverflow ||
      input.validity.stepMismatch ||
      input.validity.badInput
        ? t("{field} 必须为 {min} 到 {max} 之间的整数", {
            field: label(),
            min: input.min,
            max: input.max,
          })
        : t("请填写{field}", { field: label() }),
    );
    input.focus();
    return false;
  }
  return true;
}
function errorMessage(body) {
  return errorKeys[body.code]
    ? t(errorKeys[body.code], body.parameters || {})
    : body.error || body.message || t("请求失败");
}
function alertMessage(alert) {
  const keys = {
    ACTIVITY: "活跃线程占最大线程数 {value}%",
    QUEUE: "队列占用 {value}%",
    REJECTION: "本次采样间隔触发 {value} 次拒绝处理",
    SLOW_TASK: "本次采样间隔完成 {value} 个慢任务",
    QUEUE_TIMEOUT: "任务排队超过阈值，已等待 {value} 毫秒",
    RUN_TIMEOUT: "任务运行超过阈值，已执行 {value} 毫秒",
  };
  const message =
    keys[alert.kind] && Number.isFinite(alert.value)
      ? t(keys[alert.kind], { value: alert.value })
      : alert.message;
  if (!alert.task) return message;
  return (
    message +
    " · " +
    t("任务 {id}，阈值 {threshold} 毫秒", {
      id: alert.task.taskId,
      threshold: alert.task.thresholdMillis,
    }) +
    (alert.task.threadName
      ? " · " + t("执行线程：{thread}", { thread: alert.task.threadName })
      : "")
  );
}
function renderLoadResult() {
  if (!loadResult) return;
  const target = $("load-result");
  target.replaceChildren(
    node("strong", "", t("负载提交完成：{pool}", loadResult)),
    node(
      "div",
      "",
      t(
        "任务 {submitted} 个，接收 {accepted} 个，拒绝 {rejected} 个。",
        loadResult,
      ),
    ),
  );
  target.hidden = false;
}
function renderLanguage() {
  renderAuthentication();
  renderNavigation();
  $("view-title").textContent = t(views[currentView]);
  $("connection-text").textContent = t(connectionState);
  $("sample-time").textContent = lastSampleAt
    ? t("更新于 {time}", {
        time: new Date(lastSampleAt).toLocaleTimeString(i18n.locale()),
      })
    : t("未采样");
  renderPools();
  renderAlerts();
  renderChanges();
  syncSelect("alert-pool", true);
  if (currentView === "monitors") renderMonitor();
  if (editing && $("settings-dialog").open) {
    $("config-title").textContent = t("编辑线程池：{name}", editing);
    $("config-version").textContent = t(
      "当前版本 v{version}；保存时校验配置版本",
      editing,
    );
  }
  if (detailChange && $("detail-dialog").open) openChange(detailChange);
  if (rollbackState && $("rollback-dialog").open) renderRollback();
  renderStorage();
  renderLoadResult();
  for (const [id, source] of messages) $(id).textContent = source();
}
i18n.onChange(renderLanguage);
$("login-form").noValidate = true;
$("settings-form").noValidate = true;
$("rollback-form").noValidate = true;

$("instance-address").textContent = location.host;
showView(location.hash.slice(1) || "pools", false);
disconnect();
loadAuthentication();
