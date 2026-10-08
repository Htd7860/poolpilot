package dev.poolpilot.benchmark;

import dev.poolpilot.core.*;
import java.util.concurrent.*;
import org.openjdk.jmh.annotations.*;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@Threads(1)
public class PoolBenchmark {
  @State(Scope.Benchmark)
  public static class Reads {
    @Param({"4", "32"})
    public int workers;

    ManagedThreadPool pool;

    @Setup(Level.Trial)
    public void setup() throws Exception {
      pool = new ManagedThreadPool("reads", settings(workers), TaskDecorator.identity());
      pool.prestartAllCoreThreads();
      CountDownLatch completed = new CountDownLatch(256);
      pool.addObserver((name, sample) -> completed.countDown());
      for (int i = 0; i < 256; i++) pool.submit(() -> 42).get(2, TimeUnit.SECONDS);
      if (!completed.await(2, TimeUnit.SECONDS))
        throw new IllegalStateException("Samples not ready");
      pool.snapshot();
    }

    @TearDown(Level.Trial)
    public void close() throws Exception {
      stop(pool);
    }
  }

  @State(Scope.Benchmark)
  public static class Submissions {
    ThreadPoolExecutor plain;
    ManagedThreadPool managed;

    @Setup(Level.Trial)
    public void setup() {
      plain =
          new ThreadPoolExecutor(
              4, 4, 60, TimeUnit.SECONDS, new ResizableCapacityBlockingQueue<>(1024));
      managed = new ManagedThreadPool("submissions", settings(4), TaskDecorator.identity());
      plain.prestartAllCoreThreads();
      managed.prestartAllCoreThreads();
    }

    @TearDown(Level.Trial)
    public void close() throws Exception {
      stop(plain);
      stop(managed);
    }
  }

  @State(Scope.Benchmark)
  public static class Tracking {
    ManagedThreadPool pool;

    @Setup(Level.Trial)
    public void setup() {
      pool = new ManagedThreadPool("tracking", settings(4), TaskDecorator.identity());
      pool.setTimeoutPolicy(new TaskTimeoutPolicy(10000, 30000, 10000));
      pool.prestartAllCoreThreads();
    }

    @TearDown(Level.Trial)
    public void close() throws Exception {
      stop(pool);
    }
  }

  @Benchmark
  public long snapshotCounters(Reads state) {
    var p = state.pool;
    return p.snapshot().tasks().submitted()
        + p.snapshot().tasks().completed()
        + p.snapshot().tasks().failed()
        + p.snapshot().tasks().cancelled()
        + p.snapshot().tasks().rejected()
        + p.snapshot().tasks().callerRuns()
        + p.snapshot().tasks().slowTasks();
  }

  @Benchmark
  public long directCounters(Reads state) {
    var p = state.pool;
    return p.taskCount(TaskCounter.SUBMITTED)
        + p.taskCount(TaskCounter.COMPLETED)
        + p.taskCount(TaskCounter.FAILED)
        + p.taskCount(TaskCounter.CANCELLED)
        + p.taskCount(TaskCounter.REJECTED)
        + p.taskCount(TaskCounter.CALLER_RUNS)
        + p.taskCount(TaskCounter.SLOW_TASKS);
  }

  @Benchmark
  public int plainSubmit(Submissions state) throws Exception {
    return state.plain.submit(() -> 42).get(2, TimeUnit.SECONDS);
  }

  @Benchmark
  public int managedSubmit(Submissions state) throws Exception {
    return state.managed.submit(() -> 42).get(2, TimeUnit.SECONDS);
  }

  @Benchmark
  public int trackedSubmit(Tracking state) throws Exception {
    return state.pool.submit(() -> 42).get(2, TimeUnit.SECONDS);
  }

  private static PoolSettings settings(int workers) {
    return new PoolSettings(workers, workers, 1024, 60, false, RejectionPolicy.ABORT, 1000);
  }

  private static void stop(ThreadPoolExecutor executor) throws Exception {
    executor.shutdown();
    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
      executor.shutdownNow();
      if (!executor.awaitTermination(5, TimeUnit.SECONDS))
        throw new IllegalStateException("Pool did not terminate");
    }
  }
}
