package dev.poolpilot.spring;

import dev.poolpilot.core.*;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class PoolMetricsBinder implements AutoCloseable {
  private final MeterRegistry meterRegistry;
  private final List<Meter> meters = new ArrayList<>();
  private final Map<ManagedThreadPool, TaskObserver> observers = new HashMap<>();
  private final ManagedPoolRegistry.Subscription subscription;

  public PoolMetricsBinder(ManagedPoolRegistry registry, MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
    try {
      subscription = registry.observePools(this::bind);
    } catch (RuntimeException ex) {
      unbind();
      throw ex;
    }
  }

  private synchronized void bind(ManagedThreadPool pool) {
    String name = pool.snapshot().name();
    Tags tags = Tags.of("pool", name);
    meters.add(
        Gauge.builder("poolpilot.pool.active", pool, p -> p.getActiveCount())
            .tags(tags)
            .register(meterRegistry));
    meters.add(
        Gauge.builder("poolpilot.pool.size", pool, p -> p.getPoolSize())
            .tags(tags)
            .register(meterRegistry));
    meters.add(
        Gauge.builder("poolpilot.pool.core", pool, p -> p.getCorePoolSize())
            .tags(tags)
            .register(meterRegistry));
    meters.add(
        Gauge.builder("poolpilot.pool.max", pool, p -> p.getMaximumPoolSize())
            .tags(tags)
            .register(meterRegistry));
    meters.add(
        Gauge.builder("poolpilot.queue.size", pool, p -> p.getQueue().size())
            .tags(tags)
            .register(meterRegistry));
    meters.add(
        Gauge.builder("poolpilot.queue.capacity", pool, ManagedThreadPool::queueCapacity)
            .tags(tags)
            .register(meterRegistry));
    meters.add(
        Gauge.builder("poolpilot.queue.remaining", pool, p -> p.getQueue().remainingCapacity())
            .tags(tags)
            .register(meterRegistry));
    meters.add(
        Gauge.builder("poolpilot.config.version", pool, ManagedThreadPool::configurationVersion)
            .tags(tags)
            .register(meterRegistry));
    meters.add(
        FunctionCounter.builder(
                "poolpilot.tasks.submitted", pool, p -> p.taskCount(TaskCounter.SUBMITTED))
            .tags(tags)
            .register(meterRegistry));
    meters.add(
        FunctionCounter.builder(
                "poolpilot.tasks.completed", pool, p -> p.taskCount(TaskCounter.COMPLETED))
            .tags(tags)
            .register(meterRegistry));
    meters.add(
        FunctionCounter.builder(
                "poolpilot.tasks.failed", pool, p -> p.taskCount(TaskCounter.FAILED))
            .tags(tags)
            .register(meterRegistry));
    meters.add(
        FunctionCounter.builder(
                "poolpilot.tasks.cancelled", pool, p -> p.taskCount(TaskCounter.CANCELLED))
            .tags(tags)
            .register(meterRegistry));
    meters.add(
        FunctionCounter.builder(
                "poolpilot.tasks.rejected", pool, p -> p.taskCount(TaskCounter.REJECTED))
            .tags(tags)
            .register(meterRegistry));
    meters.add(
        FunctionCounter.builder(
                "poolpilot.tasks.caller.runs", pool, p -> p.taskCount(TaskCounter.CALLER_RUNS))
            .tags(tags)
            .register(meterRegistry));
    meters.add(
        FunctionCounter.builder(
                "poolpilot.tasks.slow", pool, p -> p.taskCount(TaskCounter.SLOW_TASKS))
            .tags(tags)
            .register(meterRegistry));
    meters.add(
        FunctionCounter.builder(
                "poolpilot.tasks.queue.timeouts",
                pool,
                p -> p.taskCount(TaskCounter.QUEUE_TIMEOUTS))
            .tags(tags)
            .register(meterRegistry));
    meters.add(
        FunctionCounter.builder(
                "poolpilot.tasks.run.timeouts", pool, p -> p.taskCount(TaskCounter.RUN_TIMEOUTS))
            .tags(tags)
            .register(meterRegistry));
    meters.add(
        FunctionCounter.builder(
                "poolpilot.tasks.tracking.dropped",
                pool,
                p -> p.taskCount(TaskCounter.TRACKING_DROPPED))
            .tags(tags)
            .register(meterRegistry));
    meters.add(
        Gauge.builder("poolpilot.tasks.tracked", pool, ManagedThreadPool::trackedTaskCount)
            .tags(tags)
            .register(meterRegistry));
    Timer wait =
        Timer.builder("poolpilot.task.wait")
            .tags(tags)
            .publishPercentileHistogram()
            .register(meterRegistry);
    meters.add(wait);
    EnumMap<TaskSample.Outcome, Timer> execution = new EnumMap<>(TaskSample.Outcome.class);
    for (TaskSample.Outcome outcome : TaskSample.Outcome.values()) {
      Timer timer =
          Timer.builder("poolpilot.task.execution")
              .tags(tags.and("outcome", outcome.name().toLowerCase(Locale.ROOT)))
              .publishPercentileHistogram()
              .serviceLevelObjectives(
                  Duration.ofMillis(10), Duration.ofMillis(100), Duration.ofSeconds(1))
              .register(meterRegistry);
      execution.put(outcome, timer);
      meters.add(timer);
    }
    TaskObserver observer =
        (poolName, sample) -> {
          wait.record(sample.waitNanos(), TimeUnit.NANOSECONDS);
          execution.get(sample.outcome()).record(sample.executionNanos(), TimeUnit.NANOSECONDS);
        };
    pool.addObserver(observer);
    observers.put(pool, observer);
  }

  @Override
  public void close() {
    // Unsubscribe before taking the binder lock: registration holds the registry lock.
    subscription.close();
    unbind();
  }

  private synchronized void unbind() {
    observers.forEach(ManagedThreadPool::removeObserver);
    meters.forEach(meterRegistry::remove);
    observers.clear();
    meters.clear();
  }
}
