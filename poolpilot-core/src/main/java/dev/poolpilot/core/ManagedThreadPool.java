package dev.poolpilot.core;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** ThreadPoolExecutor with runtime configuration and task observation. */
public final class ManagedThreadPool extends ThreadPoolExecutor {
  private record Configuration(long version, PoolSettings settings) {}

  public record Update(PoolSnapshot before, PoolSnapshot after) {}

  private final String name;
  private final Object configurationLock = new Object();
  private final ResizableCapacityBlockingQueue<Runnable> queue;
  private final TaskDecorator decorator;
  private final CopyOnWriteArrayList<TaskObserver> observers = new CopyOnWriteArrayList<>();
  private final TaskMetrics metrics = new TaskMetrics();
  private volatile Configuration configuration;
  private volatile TaskTimeoutPolicy timeoutPolicy = TaskTimeoutPolicy.disabled();
  private final ConcurrentHashMap<Long, TimedTask> trackedTasks = new ConcurrentHashMap<>();
  private final AtomicInteger trackingSize = new AtomicInteger();
  private final AtomicLong taskIds = new AtomicLong();
  private final Object trackingLock = new Object();

  public String name() {
    return name;
  }

  public void setTimeoutPolicy(TaskTimeoutPolicy policy) {
    synchronized (trackingLock) {
      timeoutPolicy = Objects.requireNonNull(policy);
    }
  }

  public int trackedTaskCount() {
    return trackingSize.get();
  }

  public void clearTimeoutTracking() {
    synchronized (trackingLock) {
      timeoutPolicy = TaskTimeoutPolicy.disabled();
      for (TimedTask task : trackedTasks.values()) task.stopTracking();
    }
  }

  public List<TaskTimeoutEvent> taskTimeouts() {
    return taskTimeouts(System.nanoTime());
  }

  List<TaskTimeoutEvent> taskTimeouts(long now) {
    List<TaskTimeoutEvent> events = new ArrayList<>();
    for (TimedTask task : trackedTasks.values()) {
      synchronized (task) {
        if (task.phase == 2) continue;
        if (task.phase == 0 && task.original instanceof Future<?> f && f.isCancelled()) {
          task.untrack();
          continue;
        }
        long threshold = task.phase == 0 ? task.policy.queueMillis() : task.policy.runMillis();
        int bit = task.phase == 0 ? 1 : 2;
        long elapsed = Math.max(0, now - (task.phase == 0 ? task.enqueued : task.startedNanos));
        if (threshold > 0
            && elapsed >= TimeUnit.MILLISECONDS.toNanos(threshold)
            && (task.timeoutFlags & bit) == 0) {
          task.timeoutFlags |= bit;
          if (task.phase == 0) metrics.queueTimeouts.increment();
          else metrics.runTimeouts.increment();
          events.add(
              new TaskTimeoutEvent(
                  task.id,
                  name,
                  task.phase == 0 ? TaskTimeoutEvent.Kind.QUEUE : TaskTimeoutEvent.Kind.RUN,
                  TimeUnit.NANOSECONDS.toMillis(elapsed),
                  threshold,
                  configurationVersion(),
                  task.worker == null ? null : task.worker.getName()));
        }
      }
    }
    return events;
  }

  public ManagedThreadPool(String name, PoolSettings settings, TaskDecorator decorator) {
    this(name, settings, decorator, 1);
  }

  ManagedThreadPool(String name, PoolSettings settings, TaskDecorator decorator, long version) {
    super(
        settings.coreSize(),
        settings.maxSize(),
        settings.keepAliveSeconds(),
        TimeUnit.SECONDS,
        new ResizableCapacityBlockingQueue<>(settings.queueCapacity()),
        threadFactory(name));
    this.name = Objects.requireNonNull(name);
    this.decorator = Objects.requireNonNull(decorator);
    this.queue = (ResizableCapacityBlockingQueue<Runnable>) getQueue();
    this.configuration = new Configuration(version, settings);
    allowCoreThreadTimeOut(settings.allowCoreTimeout());
    setRejectedExecutionHandler(
        (task, executor) -> {
          metrics.rejected.increment();
          if (!executor.isShutdown()
              && (!(task instanceof ManagedThreadPool.TimedTask timed) || !timed.forceAbort)
              && configuration.settings().rejectionPolicy() == RejectionPolicy.CALLER_RUNS) {
            metrics.callerRuns.increment();
            task.run();
          } else
            throw new RejectedExecutionException("Pool " + name + " is saturated or shut down");
        });
  }

  private static ThreadFactory threadFactory(String name) {
    AtomicInteger sequence = new AtomicInteger();
    return task -> new Thread(task, "poolpilot-" + name + "-" + sequence.incrementAndGet());
  }

  public void addObserver(TaskObserver observer) {
    observers.add(Objects.requireNonNull(observer));
  }

  public void removeObserver(TaskObserver observer) {
    observers.remove(observer);
  }

  @Override
  public void execute(Runnable command) {
    execute(command, false);
  }

  /** Submits with ABORT semantics regardless of the configured rejection policy. */
  public <T> Future<T> submitOrReject(Callable<T> command) {
    RunnableFuture<T> future = newTaskFor(Objects.requireNonNull(command));
    execute(future, true);
    return future;
  }

  /** Returns a stage linked to its original FutureTask for failure and cancellation observation. */
  public <T> CompletableFuture<T> submitStage(Callable<T> command) {
    Objects.requireNonNull(command);
    class Stage extends CompletableFuture<T> {
      FutureTask<T> task;

      @Override
      public boolean cancel(boolean mayInterruptIfRunning) {
        boolean changed = super.cancel(mayInterruptIfRunning);
        if (changed) {
          task.cancel(mayInterruptIfRunning);
          ManagedThreadPool.this.remove(task);
        }
        return changed;
      }
    }
    Stage stage = new Stage();
    stage.task =
        new FutureTask<>(command) {
          @Override
          protected void done() {
            try {
              if (isCancelled()) stage.cancel(false);
              else stage.complete(get());
            } catch (ExecutionException ex) {
              stage.completeExceptionally(ex.getCause());
            } catch (InterruptedException ex) {
              Thread.currentThread().interrupt();
              stage.completeExceptionally(ex);
            }
          }
        };
    execute(stage.task);
    return stage;
  }

  private void execute(Runnable command, boolean forceAbort) {
    Objects.requireNonNull(command);
    Runnable decorated =
        Objects.requireNonNull(decorator.decorate(command), "decorator returned null");
    metrics.submitted.increment();
    TimedTask timed = new TimedTask(command, decorated, forceAbort);
    timed.track();
    try {
      super.execute(timed);
    } catch (RuntimeException | Error ex) {
      timed.stopTracking();
      throw ex;
    }
  }

  /** Reads one counter without sampling worker state or calculating percentiles. */
  public long taskCount(TaskCounter counter) {
    return metrics.count(Objects.requireNonNull(counter));
  }

  public long configurationVersion() {
    return configuration.version();
  }

  public int queueCapacity() {
    return queue.capacity();
  }

  public PoolSnapshot snapshot() {
    synchronized (configurationLock) {
      Configuration c = configuration;
      return new PoolSnapshot(
          name,
          c.version(),
          c.settings(),
          getPoolSize(),
          getActiveCount(),
          getLargestPoolSize(),
          queue.size(),
          queue.remainingCapacity(),
          isShutdown(),
          metrics.snapshot());
    }
  }

  public Update reconfigure(PoolSettings next, long expectedVersion) {
    return reconfigure(next, expectedVersion, before -> {});
  }

  Update reconfigure(PoolSettings next, long expectedVersion, Consumer<PoolSnapshot> beforeApply) {
    Objects.requireNonNull(next);
    synchronized (configurationLock) {
      if (isShutdown()) throw new IllegalStateException("Pool is shut down");
      if (configuration.version() != expectedVersion)
        throw new VersionConflictException(configuration.version());
      if (expectedVersion >= Long.MAX_VALUE - 1)
        throw new IllegalStateException("Configuration version exhausted");
      PoolSnapshot before = snapshot();
      // Persist the candidate configuration before changing executor parameters.
      beforeApply.accept(before);
      // Lower core before lowering max; raise max before raising core.
      if (next.maxSize() < getCorePoolSize()) setCorePoolSize(next.coreSize());
      setMaximumPoolSize(next.maxSize());
      setCorePoolSize(next.coreSize());
      if (!next.allowCoreTimeout()) allowCoreThreadTimeOut(false);
      setKeepAliveTime(next.keepAliveSeconds(), TimeUnit.SECONDS);
      allowCoreThreadTimeOut(next.allowCoreTimeout());
      queue.setCapacity(next.queueCapacity());
      configuration = new Configuration(configuration.version() + 1, next);
      return new Update(before, snapshot());
    }
  }

  @Override
  public void shutdown() {
    synchronized (configurationLock) {
      super.shutdown();
    }
  }

  @Override
  public List<Runnable> shutdownNow() {
    List<Runnable> remaining;
    synchronized (configurationLock) {
      remaining = super.shutdownNow();
    }
    List<Runnable> originals = new ArrayList<>();
    for (Runnable task : remaining) {
      if (task instanceof ManagedThreadPool.TimedTask timed) {
        if (timed.original instanceof Future<?> f) f.cancel(false);
        timed.recordCancelled();
        originals.add(timed.original);
      } else originals.add(task);
    }
    return originals;
  }

  @Override
  public boolean remove(Runnable task) {
    for (Runnable queued : queue) {
      if (queued instanceof ManagedThreadPool.TimedTask timed && timed.original == task) {
        if (!super.remove(queued)) return false;
        timed.stopTracking();
        if (task instanceof Future<?> future && future.isCancelled()) timed.recordCancelled();
        return true;
      }
    }
    return super.remove(task);
  }

  @Override
  public void purge() {
    List<Runnable> cancelled =
        queue.extractMatching(
            task ->
                task instanceof ManagedThreadPool.TimedTask timed
                    && timed.original instanceof Future<?> f
                    && f.isCancelled());
    for (Runnable task : cancelled) {
      ((TimedTask) task).recordCancelled();
    }
  }

  private void record(TaskSample sample) {
    metrics.completed.increment();
    if (sample.outcome() == TaskSample.Outcome.FAILURE) metrics.failed.increment();
    if (sample.outcome() == TaskSample.Outcome.CANCELLED) metrics.cancelled.increment();
    if (sample.executionNanos()
        >= TimeUnit.MILLISECONDS.toNanos(configuration.settings().slowTaskMillis()))
      metrics.slow.increment();
    metrics.sample(sample);
    for (TaskObserver observer : observers) {
      try {
        observer.completed(name, sample);
      } catch (RuntimeException ex) {
        metrics.observerFailures.increment();
      }
    }
  }

  private final class TimedTask implements Runnable {
    private final Runnable original, decorated;
    private final boolean forceAbort;
    private final long enqueued = System.nanoTime();
    private final AtomicBoolean recorded = new AtomicBoolean();
    private final TaskTimeoutPolicy policy = timeoutPolicy;
    private final AtomicBoolean tracked = new AtomicBoolean();
    private long id, startedNanos;
    private int phase, timeoutFlags;
    private Thread worker;

    void track() {
      if (!policy.enabled()) return;
      synchronized (trackingLock) {
        if (!timeoutPolicy.enabled()) return;
        while (true) {
          int size = trackingSize.get();
          if (size >= policy.maxTrackedTasks()) {
            metrics.trackingDropped.increment();
            return;
          }
          if (trackingSize.compareAndSet(size, size + 1)) break;
        }
        id = taskIds.incrementAndGet();
        tracked.set(true);
        trackedTasks.put(id, this);
      }
    }

    void untrack() {
      if (tracked.compareAndSet(true, false)) {
        trackedTasks.remove(id, this);
        trackingSize.decrementAndGet();
      }
    }

    void stopTracking() {
      synchronized (this) {
        phase = 2;
        untrack();
      }
    }

    TimedTask(Runnable original, Runnable decorated, boolean forceAbort) {
      this.original = original;
      this.decorated = decorated;
      this.forceAbort = forceAbort;
    }

    void recordCancelled() {
      stopTracking();
      if (recorded.compareAndSet(false, true))
        record(
            new TaskSample(
                Math.max(0, System.nanoTime() - enqueued), 0, TaskSample.Outcome.CANCELLED));
    }

    @Override
    public void run() {
      if (original instanceof Future<?> f && f.isCancelled()) {
        recordCancelled();
        return;
      }
      long start = System.nanoTime();
      if (tracked.get())
        synchronized (this) {
          phase = 1;
          startedNanos = start;
          worker = Thread.currentThread();
        }
      TaskSample.Outcome outcome = TaskSample.Outcome.SUCCESS;
      metrics.started.increment();
      metrics.running.increment();
      try {
        decorated.run();
        if (original instanceof Future<?> f && !f.isDone()) {
          f.cancel(false);
          throw new IllegalStateException("TaskDecorator must run its task synchronously");
        }
        // FutureTask stores exceptions; inspect its result after run() returns.
        if (original instanceof Future<?> f && f.isDone()) {
          try {
            f.get();
          } catch (CancellationException ex) {
            outcome = TaskSample.Outcome.CANCELLED;
          } catch (ExecutionException ex) {
            outcome = TaskSample.Outcome.FAILURE;
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            outcome = TaskSample.Outcome.CANCELLED;
          }
        }
      } catch (CancellationException ex) {
        outcome = TaskSample.Outcome.CANCELLED;
        if (original instanceof Future<?> f && !f.isDone()) f.cancel(false);
        throw ex;
      } catch (RuntimeException | Error ex) {
        outcome = TaskSample.Outcome.FAILURE;
        if (original instanceof Future<?> f && !f.isDone()) f.cancel(false);
        throw ex;
      } finally {
        if (tracked.get())
          synchronized (this) {
            phase = 2;
            untrack();
          }
        metrics.running.decrement();
        if (recorded.compareAndSet(false, true))
          record(
              new TaskSample(
                  Math.max(0, start - enqueued), Math.max(0, System.nanoTime() - start), outcome));
      }
    }
  }
}
