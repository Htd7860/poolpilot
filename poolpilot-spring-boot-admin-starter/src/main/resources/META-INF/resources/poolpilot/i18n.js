"use strict";
// Chinese message IDs are the canonical copy. All substitutions remain plain text.
(() => {
  const en = {
    "{user} · {role}": "{user} · {role}",
    操作员: "Operator",
    只读: "Read only",
    配置同步: "Configuration sync",
    当前账号只有读取权限: "This account has read-only access.",
    "通知：待处理 {queued}，丢弃 {dropped}，失败 {failed} · 配置同步：已应用 {applied}，冲突 {conflicts}，失败 {failures}":
      "Notifications: {queued} queued, {dropped} dropped, {failed} failed · Configuration sync: {applied} applied, {conflicts} conflicts, {failures} failed",
    "连接后查看配置保存方式。": "Connect to see how configuration is stored.",
    "配置与变更记录已启用本地持久化。仅管理当前应用实例。":
      "Configuration and change history are persisted locally. This console manages the current application instance.",
    "配置与变更记录保存在内存，重启后恢复启动配置。":
      "Configuration and change history are in memory. Restart restores startup settings.",
    "管理令牌是访问管理接口的密钥，用于保护监控数据和调参操作。":
      "The admin token is an API access key that protects monitoring data and configuration changes.",
    回滚本次变更: "Roll back this change",
    回滚配置: "Roll back configuration",
    确认回滚: "Confirm rollback",
    "恢复到所选变更之前的完整配置，可能覆盖后续调参。回滚会产生新的版本和记录。":
      "Restore the complete configuration before the selected change. This may overwrite later adjustments. Rollback creates a new version and audit entry.",
    "回滚线程池：{name}": "Roll back thread pool: {name}",
    "当前版本 v{version} · 恢复变更 #{id} 之前的配置":
      "Current version v{version} · Restore configuration before change #{id}",
    当前配置: "Current settings",
    回滚目标: "Rollback target",
    "配置已回滚，已生成新版本和变更记录":
      "Configuration rolled back. A new version and audit entry were created.",
    操作类型: "Operation",
    来源变更: "Source change",
    回滚: "Rollback",
    调参: "Update",
    "changeId 必须为正整数": "changeId must be a positive integer.",
    "该线程池没有此变更记录，记录可能已过期":
      "Change not found for this pool; it may have expired.",
    "配置保存失败，本次变更未生效，请检查服务端存储":
      "Could not save configuration. This change was not applied. Check server storage.",
    收起菜单: "Collapse navigation",
    展开菜单: "Expand navigation",
    "PoolPilot 控制台": "PoolPilot Console",
    控制台: "Console",
    "Prometheus 指标": "Prometheus metrics",
    未连接: "Disconnected",
    已连接: "Connected",
    连接异常: "Connection error",
    断开连接: "Disconnect",
    模式: "Mode",
    主导航: "Main navigation",
    线程池管理: "Pool management",
    线程池列表: "Thread pools",
    运行监控: "Runtime metrics",
    运行记录: "Activity",
    告警记录: "Alerts",
    配置变更: "Configuration changes",
    工具: "Tools",
    负载测试: "Demo loads",
    未采样: "No samples yet",
    当前实例: "Instance",
    "当前为单实例模式。运行时配置仅在当前实例生效，重启后恢复启动配置。":
      "Standalone mode. Runtime changes apply to this instance; startup settings are restored after a restart.",
    线程池名称: "Pool name",
    请输入线程池名称: "Enter a pool name",
    拒绝策略: "Rejection policy",
    全部: "All",
    查询: "Search",
    重置: "Reset",
    刷新: "Refresh",
    查询到: "Found",
    "个线程池。": "pools.",
    "条告警记录。": "alerts.",
    "条变更记录。": "changes.",
    "线程池数据表，可横向滚动": "Thread pool table; scroll horizontally",
    "告警数据表，可横向滚动": "Alert table; scroll horizontally",
    "配置变更数据表，可横向滚动":
      "Configuration changes table; scroll horizontally",
    状态: "Status",
    "核心 / 最大": "Core / max",
    "活跃 / 实际": "Active / workers",
    队列占用: "Queue usage",
    完成任务: "Completed",
    "失败 / 拒绝": "Failed / rejected",
    "P95 等待 / 执行": "P95 wait / execution",
    操作: "Actions",
    "每 2 秒采样。P95 / P99 基于最近 256 个完成样本，包含已观测取消。":
      "Sampled every 2 seconds. P95 / P99 use the latest 256 completed samples, including observed cancellations.",
    告警类型: "Alert type",
    线程繁忙: "Thread activity",
    队列压力: "Queue pressure",
    拒绝处理: "Rejection events",
    慢任务: "Slow tasks",
    排队超时: "Queue timeout",
    运行超时: "Run timeout",
    "任务排队超过阈值，已等待 {value} 毫秒": "Queue timeout: waited {value} ms",
    "任务运行超过阈值，已执行 {value} 毫秒":
      "Run timeout: executing for {value} ms",
    "任务 {id}，阈值 {threshold} 毫秒": "Task {id}, threshold {threshold} ms",
    "执行线程：{thread}": "Worker: {thread}",
    时间: "Time",
    告警内容: "Message",
    配置版本: "Config version",
    "当前实例最多保留 200 条": "Latest 200 records per instance",
    版本: "Version",
    操作人: "Actor",
    变更原因: "Change reason",
    "向演示应用提交指定负载，查看线程池的排队、失败和拒绝情况。":
      "Submit a controlled demo load to observe queueing, failures and rejections.",
    目标线程池: "Target pool",
    负载类型: "Load profile",
    任务数: "Tasks",
    单任务耗时: "Duration per task",
    失败设置: "Failure pattern",
    平稳负载: "Steady load",
    无: "None",
    提交平稳负载: "Submit steady load",
    队列拥堵: "Saturation",
    提交队列拥堵: "Submit saturation",
    部分失败: "Partial failure",
    "每 3 个任务失败 1 个": "Every third task fails",
    提交部分失败: "Submit partial failure",
    提交慢任务: "Submit slow tasks",
    "仅演示应用提供此接口，最多 200 个任务，单任务最长 2 秒，仅支持 ABORT。已拒绝任务不会自动重试。":
      "Demo application only: up to 200 tasks, at most 2 seconds each, ABORT policy required. Rejected tasks are not retried.",
    查看运行监控: "View runtime metrics",
    "采样间隔：2 秒": "Sampling: every 2 seconds",
    指标为运行快照: "Metrics are runtime snapshots",
    队列缩容保留已排队任务: "Queue shrink preserves queued tasks",
    连接管理台: "Connect to console",
    "请输入当前实例的管理令牌。": "Enter the admin token for this instance.",
    管理令牌: "Admin token",
    请输入管理令牌: "Enter admin token",
    "令牌仅保存在当前页面内存中，由应用部署者配置。":
      "The deployment provides the token. It stays in page memory only.",
    "沿用业务应用当前的登录身份，需要具有线程池管理权限。":
      "Use your current application session with thread-pool management permission.",
    使用当前登录身份: "Use current application session",
    重试连接: "Retry connection",
    "无法读取认证方式，请确认管理接口已启用。":
      "Cannot read the authentication mode. Check that the management API is enabled.",
    "请先登录业务应用，并确认具有线程池管理权限。":
      "Sign in to the application with thread-pool management permission first.",
    编辑线程池: "Edit thread pool",
    关闭: "Close",
    关闭详情: "Close details",
    核心线程数: "Core threads",
    最大线程数: "Max threads",
    队列容量: "Queue capacity",
    "空闲存活时间（秒）": "Keep-alive (seconds)",
    "慢任务阈值（毫秒）": "Slow task threshold (ms)",
    核心线程超时: "Core thread timeout",
    允许核心线程空闲超时: "Allow idle core threads to time out",
    "缩容保留已排队任务。CALLER_RUNS 会占用提交任务的线程。":
      "Shrinking preserves queued tasks. CALLER_RUNS executes on the submitting thread.",
    请输入变更原因: "Enter a change reason",
    读取最新配置: "Reload latest settings",
    保存并生效: "Save and apply",
    变更详情: "Change details",
    界面语言: "Interface language",
    "无法读取服务响应，请确认应用仍在运行":
      "Cannot read the server response. Check that the application is running.",
    请求失败: "Request failed",
    "共 {count} 条 / 每页 {size} 条": "{count} records / {size} per page",
    "无法连接服务，请确认应用仍在运行":
      "Cannot connect to the server. Check that the application is running.",
    "请填写{field}": "Enter {field}.",
    "{field} 必须为 {min} 到 {max} 之间的整数":
      "{field} must be an integer between {min} and {max}.",
    上一页: "Previous",
    下一页: "Next",
    "第 {page} 页": "Page {page}",
    已关闭: "Shut down",
    繁忙: "Busy",
    运行中: "Running",
    编辑: "Edit",
    监控: "Metrics",
    没有符合查询条件的线程池: "No pools match the search.",
    "尚未配置线程池，请在启动配置中添加线程池。":
      "No pools configured. Add a pool to the startup configuration.",
    没有符合查询条件的告警: "No alerts match the search.",
    暂无告警记录: "No alerts recorded.",
    详情: "Details",
    没有符合查询条件的变更: "No changes match the search.",
    暂无配置变更记录: "No configuration changes recorded.",
    "最近 {count} 次采样的队列长度趋势":
      "Queue length over the latest {count} samples",
    尚未配置线程池: "No pools configured.",
    "活跃 / 最大线程": "Active / max threads",
    "队列占用 / 容量": "Queued / capacity",
    已完成任务: "Completed tasks",
    拒绝事件: "Rejection events",
    任务统计: "Task statistics",
    "配置 v{version} · {policy}": "Config v{version} · {policy}",
    累计指标: "Cumulative metric",
    计数: "Count",
    "耗时 / 样本": "Timing / samples",
    数值: "Value",
    提交尝试: "Submission attempts",
    "等待 P95": "Wait P95",
    "执行 P95": "Execution P95",
    失败任务: "Failed tasks",
    "执行 P99": "Execution P99",
    取消任务: "Cancelled tasks",
    平均等待: "Average wait",
    平均执行: "Average execution",
    调用方执行: "Caller runs",
    窗口样本数: "Window samples",
    队列长度趋势: "Queue length",
    "最近 {count} 次采样 · 最高 {max}": "Latest {count} samples · peak {max}",
    配置变更详情: "Configuration change details",
    变更时间: "Changed at",
    参数: "Setting",
    变更前: "Before",
    变更后: "After",
    是: "Yes",
    否: "No",
    "更新于 {time}": "Updated at {time}",
    "请求超时，请确认服务状态": "Request timed out. Check server status.",
    "编辑线程池：{name}": "Edit thread pool: {name}",
    "当前版本 v{version}；保存时校验配置版本":
      "Current version v{version}; checked when saving",
    "连接超时，请确认应用已启动":
      "Connection timed out. Check that the application is running.",
    核心线程数不能大于最大线程数: "Core threads cannot exceed max threads.",
    "配置已保存，当前实例已生效":
      "Settings saved and applied to this instance.",
    "；点击“读取最新配置”后重新填写。":
      " Use “Reload latest settings” before editing again.",
    "负载提交完成：{pool}": "Load submitted: {pool}",
    "任务 {submitted} 个，接收 {accepted} 个，拒绝 {rejected} 个。":
      "{submitted} tasks: {accepted} accepted, {rejected} rejected.",
    "当前应用未提供演示负载接口。":
      "This application does not provide the demo load endpoint.",
    "活跃线程占最大线程数 {value}%": "Active threads: {value}% of maximum",
    "队列占用 {value}%": "Queue usage: {value}%",
    "本次采样间隔触发 {value} 次拒绝处理":
      "{value} rejection events in this sampling interval",
    "本次采样间隔完成 {value} 个慢任务":
      "{value} slow tasks completed in this sampling interval",
    "管理令牌不正确，请重新连接": "Invalid admin token. Please reconnect.",
    "配置已被修改，请读取最新配置后重试。当前版本：{version}":
      "Configuration has changed. Reload before retrying. Current version: {version}",
    找不到指定线程池: "The requested pool does not exist.",
    "线程池已关闭，无法修改配置":
      "The pool is shut down and cannot be reconfigured.",
    "必须提供全部配置参数，且不能为 null":
      "Every settings field is required and must be non-null.",
    "expectedVersion 必须为正整数":
      "expectedVersion must be a positive integer.",
    "请填写 1 到 200 字的变更原因":
      "A change reason of 1 to 200 characters is required.",
    "线程数必须满足 0 ≤ coreSize ≤ maxSize ≤ 1024，maxSize 至少为 1":
      "Threads must satisfy 0 <= coreSize <= maxSize <= 1024, with maxSize >= 1.",
    "queueCapacity 必须在 1 到 1000000 之间":
      "queueCapacity must be between 1 and 1000000.",
    "keepAliveSeconds 必须在 0 到 86400 之间；核心线程超时时必须大于 0":
      "keepAliveSeconds must be between 0 and 86400, and positive when core timeout is enabled.",
    "slowTaskMillis 必须在 1 到 86400000 之间":
      "slowTaskMillis must be between 1 and 86400000.",
    必须提供线程池名称: "A pool name is required.",
    "任务数 1–200，耗时 1–2000 毫秒，失败间隔 0–200":
      "Tasks: 1–200; duration: 1–2000 ms; failure interval: 0–200.",
    "演示负载只支持 ABORT 策略；业务接入可使用 CALLER_RUNS":
      "Demo loads require ABORT. Business tasks may use CALLER_RUNS.",
    "正在提交演示任务，请稍后重试":
      "A demo load is being submitted. Try again shortly.",
    "请求参数无效，请检查 JSON 和配置取值":
      "Invalid request. Check JSON and configuration values.",
  };
  const storageKey = "poolpilot.locale";
  let language = navigator.language.toLowerCase().startsWith("zh")
    ? "zh"
    : "en";
  try {
    const saved = localStorage.getItem(storageKey);
    if (saved === "zh" || saved === "en") language = saved;
  } catch {
    /* Storage may be disabled. Switching still works for this page. */
  }
  const missing = new Set();
  const t = (key, parameters = {}) => {
    if (/[\u4e00-\u9fff]/.test(key) && !Object.hasOwn(en, key))
      missing.add(key);
    const value = language === "en" ? en[key] || key : key;
    return value.replace(/\{(\w+)\}/g, (match, name) =>
      Object.hasOwn(parameters, name) ? String(parameters[name]) : match,
    );
  };
  // Capture static text once so language changes preserve inputs and nested counter elements.
  const bindings = [];
  const walker = document.createTreeWalker(
    document.documentElement,
    NodeFilter.SHOW_TEXT,
  );
  for (let text = walker.nextNode(); text; text = walker.nextNode()) {
    if (text.parentElement.closest("script,style,.language-picker")) continue;
    if (!/[\u4e00-\u9fff]/.test(text.nodeValue)) continue;
    const original = text.nodeValue;
    const key = original.trim().replace(/\s+/g, " ");
    bindings.push(() => {
      text.nodeValue = original.replace(original.trim(), t(key));
    });
  }
  document.querySelectorAll("[placeholder],[aria-label]").forEach((element) => {
    for (const attribute of ["placeholder", "aria-label"]) {
      const key = element.getAttribute(attribute);
      if (key && /[\u4e00-\u9fff]/.test(key))
        bindings.push(() => element.setAttribute(attribute, t(key)));
    }
  });
  const listeners = [];
  const apply = () => {
    document.documentElement.lang = language === "zh" ? "zh-CN" : "en";
    bindings.forEach((binding) => binding());
    document.querySelectorAll(".language-picker").forEach((select) => {
      select.value = language;
    });
  };
  document.querySelectorAll(".language-picker").forEach((select) => {
    select.addEventListener("change", () => {
      language = select.value === "zh" ? "zh" : "en";
      try {
        localStorage.setItem(storageKey, language);
      } catch {
        /* Optional preference storage. */
      }
      apply();
      listeners.forEach((listener) => listener());
    });
  });
  window.PoolPilotI18n = Object.freeze({
    t,
    locale: () => (language === "zh" ? "zh-CN" : "en-US"),
    onChange: (listener) => listeners.push(listener),
    missing,
  });
  apply();
})();
