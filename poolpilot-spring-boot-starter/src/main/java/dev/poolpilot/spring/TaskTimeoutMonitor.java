package dev.poolpilot.spring;

import dev.poolpilot.core.*;
import java.util.concurrent.*;
import org.slf4j.LoggerFactory;

/** Scans bounded per-pool task tracking independently of configuration snapshots. */
public final class TaskTimeoutMonitor implements AutoCloseable {
  private final ManagedPoolRegistry registry;
  private final PoolAlertMonitor alerts;
  private final PoolPilotProperties.Timeouts settings;
  private final TaskTimeoutPolicy policy;
  private final ManagedPoolRegistry.Subscription subscription;
  private ScheduledExecutorService scheduler;
  private volatile boolean closed;

  public TaskTimeoutMonitor(
      ManagedPoolRegistry registry,
      PoolAlertMonitor alerts,
      PoolPilotProperties.Timeouts settings) {
    this.registry = registry;
    this.alerts = alerts;
    this.settings = settings;
    this.policy = settings.policy();
    subscription = registry.observePools(pool -> pool.setTimeoutPolicy(policy));
  }

  public synchronized void start() {
    if (closed) throw new IllegalStateException("Task timeout monitor is closed");
    if (!policy.enabled() || scheduler != null) return;
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            task -> {
              Thread thread = new Thread(task, "poolpilot-task-timeouts");
              thread.setDaemon(true);
              return thread;
            });
    scheduler.scheduleWithFixedDelay(
        () -> {
          try {
            pollOnce();
          } catch (RuntimeException ex) {
            LoggerFactory.getLogger(getClass()).warn("Task timeout scan failed", ex);
          }
        },
        settings.getIntervalMillis(),
        settings.getIntervalMillis(),
        TimeUnit.MILLISECONDS);
  }

  public void pollOnce() {
    if (closed) return;
    for (ManagedThreadPool pool : registry.pools())
      for (TaskTimeoutEvent event : pool.taskTimeouts()) {
        if (closed) return;
        alerts.onTaskTimeout(event);
      }
  }

  @Override
  public void close() {
    synchronized (this) {
      if (closed) return;
      closed = true;
      if (scheduler != null) scheduler.shutdownNow();
    }
    subscription.close();
    for (ManagedThreadPool pool : registry.pools()) pool.clearTimeoutTracking();
  }
}
