package dev.poolpilot.core;

public record TaskTimeoutPolicy(long queueMillis, long runMillis, int maxTrackedTasks) {
  public TaskTimeoutPolicy {
    if (queueMillis < 0
        || queueMillis > 86400000
        || runMillis < 0
        || runMillis > 86400000
        || maxTrackedTasks < 1
        || maxTrackedTasks > 1000000)
      throw new IllegalArgumentException("Invalid task timeout or tracking limit");
  }

  public boolean enabled() {
    return queueMillis > 0 || runMillis > 0;
  }

  public static TaskTimeoutPolicy disabled() {
    return new TaskTimeoutPolicy(0, 0, 10000);
  }
}
