package dev.poolpilot.core;

public record TaskSample(long waitNanos, long executionNanos, Outcome outcome) {
  public enum Outcome {
    SUCCESS,
    FAILURE,
    CANCELLED
  }
}
