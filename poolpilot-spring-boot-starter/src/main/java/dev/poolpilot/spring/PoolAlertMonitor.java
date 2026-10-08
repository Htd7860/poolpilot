package dev.poolpilot.spring;

import dev.poolpilot.core.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Monotonic cooldown per pool/type; counters are sampled as interval deltas. */
public final class PoolAlertMonitor implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(PoolAlertMonitor.class);
  private final ManagedPoolRegistry registry;
  private final PoolPilotProperties.Alerts settings;
  private final List<PoolAlertListener> listeners;
  private final BoundedEventLog<PoolAlert> alerts = new BoundedEventLog<>(200);
  private final Map<String, Long> lastEmitted = new HashMap<>(),
      previousRejected = new HashMap<>(),
      previousSlow = new HashMap<>();
  private final AtomicLong ids = new AtomicLong();
  private final LongSupplier nanoTime;
  private final ThreadPoolExecutor notifications;
  private final LongAdder accepted = new LongAdder(),
      completed = new LongAdder(),
      failed = new LongAdder(),
      dropped = new LongAdder();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Object lifecycleLock = new Object();
  private final Object samplingLock = new Object();
  private final Object cooldownLock = new Object();
  private volatile ScheduledExecutorService scheduler;

  public PoolAlertMonitor(
      ManagedPoolRegistry registry,
      PoolPilotProperties.Alerts settings,
      List<PoolAlertListener> listeners) {
    this(registry, settings, listeners, System::nanoTime);
  }

  PoolAlertMonitor(
      ManagedPoolRegistry registry,
      PoolPilotProperties.Alerts settings,
      List<PoolAlertListener> listeners,
      LongSupplier nanoTime) {
    settings.validate();
    this.registry = registry;
    this.settings = settings;
    this.listeners = List.copyOf(listeners);
    this.nanoTime = nanoTime;
    AtomicLong threads = new AtomicLong();
    notifications =
        new ThreadPoolExecutor(
            settings.getNotificationThreads(),
            settings.getNotificationThreads(),
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(settings.getNotificationQueueCapacity()),
            task -> {
              Thread thread =
                  new Thread(task, "poolpilot-alert-delivery-" + threads.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
  }

  public void start() {
    synchronized (lifecycleLock) {
      if (closed.get()) throw new IllegalStateException("Alert monitor is closed");
      if (!settings.isEnabled() || scheduler != null) return;
      scheduler =
          Executors.newSingleThreadScheduledExecutor(
              task -> {
                Thread t = new Thread(task, "poolpilot-alert-monitor");
                t.setDaemon(true);
                return t;
              });
      scheduler.scheduleWithFixedDelay(
          () -> {
            try {
              pollOnce();
            } catch (RuntimeException ex) {
              log.warn("PoolPilot alert sampling failed", ex);
            }
          },
          settings.getIntervalMillis(),
          settings.getIntervalMillis(),
          TimeUnit.MILLISECONDS);
    }
  }

  public void pollOnce() {
    synchronized (samplingLock) {
      if (!settings.isEnabled() || closed.get()) return;
      for (PoolSnapshot pool : registry.snapshots()) {
        if (pool.shutdown()) continue;
        int activity = (int) (100L * pool.activeCount() / pool.settings().maxSize());
        int occupancy =
            (int)
                Math.min(
                    Integer.MAX_VALUE, 100L * pool.queueSize() / pool.settings().queueCapacity());
        if (activity >= settings.getActivityPercent())
          emit(pool, PoolAlert.Kind.ACTIVITY, activity);
        if (occupancy >= settings.getQueuePercent()) emit(pool, PoolAlert.Kind.QUEUE, occupancy);
        long rejected = pool.tasks().rejected(), slow = pool.tasks().slowTasks();
        long rejectionDelta = rejected - previousRejected.getOrDefault(pool.name(), 0L);
        long slowDelta = slow - previousSlow.getOrDefault(pool.name(), 0L);
        previousRejected.put(pool.name(), rejected);
        previousSlow.put(pool.name(), slow);
        if (rejectionDelta > 0) emit(pool, PoolAlert.Kind.REJECTION, rejectionDelta);
        if (slowDelta > 0) emit(pool, PoolAlert.Kind.SLOW_TASK, slowDelta);
      }
    }
  }

  private void emit(PoolSnapshot pool, PoolAlert.Kind kind, long value) {
    emit(pool.name(), pool.version(), kind, value, null);
  }

  public void onTaskTimeout(TaskTimeoutEvent event) {
    emit(
        event.pool(),
        event.configVersion(),
        event.kind() == TaskTimeoutEvent.Kind.QUEUE
            ? PoolAlert.Kind.QUEUE_TIMEOUT
            : PoolAlert.Kind.RUN_TIMEOUT,
        event.elapsedMillis(),
        event);
  }

  private void emit(
      String poolName, long version, PoolAlert.Kind kind, long value, TaskTimeoutEvent task) {
    if (closed.get()) return;
    String key = poolName + ":" + kind;
    long now = nanoTime.getAsLong();
    synchronized (cooldownLock) {
      Long last = lastEmitted.get(key);
      if (last != null && now - last < TimeUnit.SECONDS.toNanos(settings.getCooldownSeconds()))
        return;
      lastEmitted.put(key, now);
    }
    PoolAlert alert =
        new PoolAlert(
            ids.incrementAndGet(),
            Instant.now(),
            poolName,
            kind,
            ConsoleMessages.alert(kind, value, Locale.ENGLISH),
            value,
            version,
            task);
    alerts.add(alert);
    if (listeners.isEmpty()) return;
    try {
      notifications.execute(
          () -> {
            try {
              for (PoolAlertListener listener : listeners) {
                try {
                  listener.onAlert(alert);
                } catch (RuntimeException ex) {
                  failed.increment();
                  log.warn("PoolPilot alert listener failed", ex);
                }
              }
            } finally {
              completed.increment();
            }
          });
      accepted.increment();
    } catch (RejectedExecutionException ex) {
      dropped.increment();
    }
  }

  public AlertDeliveryStats deliveryStats() {
    return new AlertDeliveryStats(
        accepted.sum(),
        completed.sum(),
        failed.sum(),
        dropped.sum(),
        notifications.getQueue().size(),
        notifications.getActiveCount(),
        closed.get());
  }

  public long deliveryAccepted() {
    return accepted.sum();
  }

  public long deliveryCompleted() {
    return completed.sum();
  }

  public long deliveryFailed() {
    return failed.sum();
  }

  public long deliveryDropped() {
    return dropped.sum();
  }

  public int deliveryQueued() {
    return notifications.getQueue().size();
  }

  public int deliveryActive() {
    return notifications.getActiveCount();
  }

  public List<PoolAlert> alerts() {
    return alerts.newestFirst();
  }

  @Override
  public void close() {
    synchronized (lifecycleLock) {
      if (!closed.compareAndSet(false, true)) return;
      if (scheduler != null) scheduler.shutdownNow();
    }
    notifications.shutdown();
    try {
      if (!notifications.awaitTermination(
          settings.getNotificationShutdownMillis(), TimeUnit.MILLISECONDS))
        dropped.add(notifications.shutdownNow().size());
    } catch (InterruptedException ex) {
      dropped.add(notifications.shutdownNow().size());
      Thread.currentThread().interrupt();
    }
  }
}
