package dev.poolpilot.spring;

import java.util.Locale;
import java.util.Map;

/** Machine-readable codes stay stable; human-readable messages follow Accept-Language. */
public final class ConsoleMessages {
  private ConsoleMessages() {}

  public record ApiError(String code, String error, Map<String, Object> parameters) {}

  public static final class InvalidRequest extends IllegalArgumentException {
    private final String code;

    public InvalidRequest(String code) {
      super(code);
      this.code = code;
    }

    public String code() {
      return code;
    }
  }

  public static ApiError error(String code, Locale locale) {
    return error(code, locale, Map.of());
  }

  public static ApiError error(String code, Locale locale, Map<String, Object> parameters) {
    boolean zh = "zh".equals(locale.getLanguage());
    String message =
        switch (code) {
          case "UNAUTHORIZED" -> zh ? "管理令牌不正确，请重新连接" : "Invalid admin token. Please reconnect.";
          case "VERSION_CONFLICT" ->
              zh
                  ? "配置已被修改，请读取最新配置后重试。当前版本：{version}"
                  : "Configuration has changed. Reload before retrying. Current version: {version}";
          case "POOL_NOT_FOUND" -> zh ? "找不到指定线程池" : "The requested pool does not exist.";
          case "CHANGE_REQUIRED" -> zh ? "changeId 必须为正整数" : "changeId must be a positive integer.";
          case "CHANGE_NOT_FOUND" ->
              zh ? "该线程池没有此变更记录，记录可能已过期" : "Change not found for this pool; it may have expired.";
          case "FORBIDDEN" -> zh ? "当前账号没有修改权限" : "This account has read-only access.";
          case "PERSISTENCE_FAILED" ->
              zh
                  ? "配置保存失败，本次变更未生效，请检查服务端存储"
                  : "Could not save configuration. This change was not applied. Check server"
                      + " storage.";
          case "POOL_CLOSED" ->
              zh ? "线程池已关闭，无法修改配置" : "The pool is shut down and cannot be reconfigured.";
          case "SETTINGS_REQUIRED" ->
              zh
                  ? "必须提供全部配置参数，且不能为 null"
                  : "Every settings field is required and must be non-null.";
          case "VERSION_REQUIRED" ->
              zh ? "expectedVersion 必须为正整数" : "expectedVersion must be a positive integer.";
          case "REASON_REQUIRED" ->
              zh ? "请填写 1 到 200 字的变更原因" : "A change reason of 1 to 200 characters is required.";
          case "THREAD_RANGE" ->
              zh
                  ? "线程数必须满足 0 ≤ coreSize ≤ maxSize ≤ 1024，maxSize 至少为 1"
                  : "Threads must satisfy 0 <= coreSize <= maxSize <= 1024, with maxSize >= 1.";
          case "QUEUE_RANGE" ->
              zh
                  ? "queueCapacity 必须在 1 到 1000000 之间"
                  : "queueCapacity must be between 1 and 1000000.";
          case "KEEP_ALIVE_RANGE" ->
              zh
                  ? "keepAliveSeconds 必须在 0 到 86400 之间；核心线程超时时必须大于 0"
                  : "keepAliveSeconds must be between 0 and 86400, and positive when core timeout"
                      + " is enabled.";
          case "SLOW_TASK_RANGE" ->
              zh
                  ? "slowTaskMillis 必须在 1 到 86400000 之间"
                  : "slowTaskMillis must be between 1 and 86400000.";
          case "POOL_REQUIRED" -> zh ? "必须提供线程池名称" : "A pool name is required.";
          case "LOAD_RANGE" ->
              zh
                  ? "任务数 1–200，耗时 1–2000 毫秒，失败间隔 0–200"
                  : "Tasks: 1–200; duration: 1–2000 ms; failure interval: 0–200.";
          case "LOAD_POLICY" ->
              zh
                  ? "演示负载只支持 ABORT 策略；业务接入可使用 CALLER_RUNS"
                  : "Demo loads require ABORT. Business tasks may use CALLER_RUNS.";
          case "LOAD_BUSY" ->
              zh ? "正在提交演示任务，请稍后重试" : "A demo load is being submitted. Try again shortly.";
          default ->
              zh
                  ? "请求参数无效，请检查 JSON 和配置取值"
                  : "Invalid request. Check JSON and configuration values.";
        };
    for (var entry : parameters.entrySet())
      message = message.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
    return new ApiError(code, message, Map.copyOf(parameters));
  }

  public static String alert(PoolAlert.Kind kind, long value, Locale locale) {
    boolean zh = "zh".equals(locale.getLanguage());
    return switch (kind) {
      case ACTIVITY ->
          zh ? "活跃线程占最大线程数 " + value + "%" : "Active threads: " + value + "% of maximum";
      case QUEUE -> zh ? "队列占用 " + value + "%" : "Queue usage: " + value + "%";
      case REJECTION ->
          zh
              ? "本次采样间隔触发 " + value + " 次拒绝处理"
              : value + " rejection events in this sampling interval";
      case SLOW_TASK ->
          zh
              ? "本次采样间隔完成 " + value + " 个慢任务"
              : value + " slow tasks completed in this sampling interval";
      case QUEUE_TIMEOUT ->
          zh ? "任务排队超过阈值，已等待 " + value + " 毫秒" : "Queue timeout: waited " + value + " ms";
      case RUN_TIMEOUT ->
          zh ? "任务运行超过阈值，已执行 " + value + " 毫秒" : "Run timeout: executing for " + value + " ms";
    };
  }
}
