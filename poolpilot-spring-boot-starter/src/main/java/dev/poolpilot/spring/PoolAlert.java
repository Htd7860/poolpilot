package dev.poolpilot.spring;

import dev.poolpilot.core.TaskTimeoutEvent;
import java.time.Instant;

public record PoolAlert(
    long id,
    Instant timestamp,
    String pool,
    Kind kind,
    String message,
    long value,
    long configVersion,
    TaskTimeoutEvent task) {
  public PoolAlert(
      long id,
      Instant timestamp,
      String pool,
      Kind kind,
      String message,
      long value,
      long configVersion) {
    this(id, timestamp, pool, kind, message, value, configVersion, null);
  }

  public enum Kind {
    ACTIVITY,
    QUEUE,
    REJECTION,
    SLOW_TASK,
    QUEUE_TIMEOUT,
    RUN_TIMEOUT
  }
}
