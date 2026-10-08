package dev.poolpilot.core;

public record TaskTimeoutEvent(
    long taskId,
    String pool,
    Kind kind,
    long elapsedMillis,
    long thresholdMillis,
    long configVersion,
    String threadName) {
  public enum Kind {
    QUEUE,
    RUN
  }
}
