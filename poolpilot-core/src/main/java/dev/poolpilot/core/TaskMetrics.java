package dev.poolpilot.core;

import java.util.Arrays;
import java.util.concurrent.atomic.LongAdder;

final class TaskMetrics {
  final LongAdder submitted = new LongAdder(),
      started = new LongAdder(),
      completed = new LongAdder(),
      failed = new LongAdder(),
      cancelled = new LongAdder(),
      rejected = new LongAdder(),
      callerRuns = new LongAdder(),
      slow = new LongAdder(),
      observerFailures = new LongAdder(),
      running = new LongAdder(),
      queueTimeouts = new LongAdder(),
      runTimeouts = new LongAdder(),
      trackingDropped = new LongAdder();
  private final long[] waits = new long[256], executions = new long[256];
  private int cursor, size;
  private double totalWait, totalExecution;
  private long samples, cachedAt = -1;
  private double cachedWaitP95, cachedExecutionP95, cachedExecutionP99;

  long count(TaskCounter counter) {
    return switch (counter) {
      case SUBMITTED -> submitted.sum();
      case STARTED -> started.sum();
      case COMPLETED -> completed.sum();
      case FAILED -> failed.sum();
      case CANCELLED -> cancelled.sum();
      case REJECTED -> rejected.sum();
      case CALLER_RUNS -> callerRuns.sum();
      case SLOW_TASKS -> slow.sum();
      case OBSERVER_FAILURES -> observerFailures.sum();
      case RUNNING -> running.sum();
      case QUEUE_TIMEOUTS -> queueTimeouts.sum();
      case RUN_TIMEOUTS -> runTimeouts.sum();
      case TRACKING_DROPPED -> trackingDropped.sum();
    };
  }

  synchronized void sample(TaskSample sample) {
    waits[cursor] = sample.waitNanos();
    executions[cursor] = sample.executionNanos();
    cursor = (cursor + 1) % waits.length;
    size = Math.min(waits.length, size + 1);
    totalWait += sample.waitNanos();
    totalExecution += sample.executionNanos();
    samples++;
  }

  synchronized TaskStats snapshot() {
    if (cachedAt != samples) {
      long[] w = Arrays.copyOf(waits, size), e = Arrays.copyOf(executions, size);
      Arrays.sort(w);
      Arrays.sort(e);
      cachedWaitP95 = percentile(w, .95);
      cachedExecutionP95 = percentile(e, .95);
      cachedExecutionP99 = percentile(e, .99);
      cachedAt = samples;
    }
    return new TaskStats(
        submitted.sum(),
        started.sum(),
        completed.sum(),
        failed.sum(),
        cancelled.sum(),
        rejected.sum(),
        callerRuns.sum(),
        slow.sum(),
        observerFailures.sum(),
        running.sum(),
        samples == 0 ? 0 : totalWait / (double) samples / 1e6,
        samples == 0 ? 0 : totalExecution / (double) samples / 1e6,
        cachedWaitP95,
        cachedExecutionP95,
        cachedExecutionP99,
        size,
        queueTimeouts.sum(),
        runTimeouts.sum(),
        trackingDropped.sum());
  }

  private double percentile(long[] values, double q) {
    return values.length == 0 ? 0 : values[(int) Math.ceil(q * values.length) - 1] / 1e6;
  }
}
