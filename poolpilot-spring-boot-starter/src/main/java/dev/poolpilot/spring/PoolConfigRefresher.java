package dev.poolpilot.spring;

import dev.poolpilot.core.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.slf4j.LoggerFactory;

public final class PoolConfigRefresher implements AutoCloseable {
  public record Status(
      String application,
      String instance,
      long applied,
      long stale,
      long conflicts,
      long failures,
      long lastSuccessEpochMillis,
      boolean closed) {}

  private final ManagedPoolRegistry registry;
  private final List<PoolConfigSource> sources;
  private final String application, instance;
  private final long intervalMillis;
  private final ScheduledExecutorService scheduler;
  private final AtomicBoolean closed = new AtomicBoolean(),
      started = new AtomicBoolean(),
      polling = new AtomicBoolean();
  private final LongAdder applied = new LongAdder(),
      stale = new LongAdder(),
      conflicts = new LongAdder(),
      failures = new LongAdder();
  private volatile long lastSuccess;
  private final Object lifecycleLock = new Object();

  public PoolConfigRefresher(
      ManagedPoolRegistry registry,
      List<PoolConfigSource> sources,
      String application,
      String instance,
      long intervalMillis) {
    if (application == null
        || !application.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")
        || instance == null
        || !instance.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")
        || intervalMillis < 250
        || intervalMillis > 60000
        || sources.size() > 8)
      throw new IllegalArgumentException("Invalid configuration refresh scope or limits");
    this.registry = registry;
    this.sources = List.copyOf(sources);
    if (sources.stream().map(PoolConfigSource::id).distinct().count() != sources.size())
      throw new IllegalArgumentException("Configuration source IDs must be unique");
    for (var source : sources)
      if (!source.id().matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}"))
        throw new IllegalArgumentException("Invalid configuration source ID");
    this.application = application;
    this.instance = instance;
    this.intervalMillis = intervalMillis;
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            task -> {
              var thread = new Thread(task, "poolpilot-config-refresh");
              thread.setDaemon(true);
              return thread;
            });
  }

  public void start() {
    synchronized (lifecycleLock) {
      if (closed.get()) throw new IllegalStateException("Configuration refresher is closed");
      if (sources.isEmpty() || !started.compareAndSet(false, true)) return;
      scheduler.scheduleWithFixedDelay(this::pollOnce, 0, intervalMillis, TimeUnit.MILLISECONDS);
    }
  }

  public void pollOnce() {
    if (closed.get() || !polling.compareAndSet(false, true)) return;
    try {
      for (var source : sources) {
        if (closed.get()) return;
        try {
          PoolConfigDocument document = Objects.requireNonNull(source.fetch());
          if (closed.get()) return;
          if (!application.equals(document.application()))
            throw new IllegalArgumentException("Configuration application does not match");
          for (var update : document.updates()) {
            if (closed.get()) return;
            try {
              var result =
                  registry.applyExternal(
                      source.id(),
                      document.revision(),
                      update.pool(),
                      update.settings(),
                      update.expectedVersion(),
                      update.reason());
              if (result == ManagedPoolRegistry.ExternalResult.APPLIED) applied.increment();
              else stale.increment();
            } catch (VersionConflictException ex) {
              conflicts.increment();
              LoggerFactory.getLogger(getClass())
                  .warn(
                      "Configuration conflict for source={}, pool={}, actualVersion={}",
                      source.id(),
                      update.pool(),
                      ex.actualVersion());
            } catch (RuntimeException ex) {
              failures.increment();
              LoggerFactory.getLogger(getClass())
                  .warn(
                      "Configuration update failed for source={}, pool={}, type={}",
                      source.id(),
                      update.pool(),
                      ex.getClass().getSimpleName());
            }
          }
          lastSuccess = System.currentTimeMillis();
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          return;
        } catch (Exception ex) {
          failures.increment();
          LoggerFactory.getLogger(getClass())
              .warn(
                  "Configuration refresh failed for source={}, type={}",
                  source.id(),
                  ex.getClass().getSimpleName());
        }
      }
    } finally {
      polling.set(false);
    }
  }

  public Status status() {
    return new Status(
        application,
        instance,
        applied.sum(),
        stale.sum(),
        conflicts.sum(),
        failures.sum(),
        lastSuccess,
        closed.get());
  }

  @Override
  public void close() {
    synchronized (lifecycleLock) {
      if (!closed.compareAndSet(false, true)) return;
      scheduler.shutdownNow();
    }
    for (var source : sources) {
      try {
        source.close();
      } catch (RuntimeException ex) {
        failures.increment();
      }
    }
  }
}
