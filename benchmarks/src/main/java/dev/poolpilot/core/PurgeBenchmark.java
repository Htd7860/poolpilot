package dev.poolpilot.core;

import java.util.concurrent.*;
import org.openjdk.jmh.annotations.*;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(2)
@Threads(1)
public class PurgeBenchmark {
  @State(Scope.Thread)
  public static class QueueState {
    @Param({"1000", "10000"})
    public int tasks;

    ResizableCapacityBlockingQueue<FutureTask<Integer>> queue;
    FutureTask<Integer>[] values;

    @SuppressWarnings("unchecked")
    @Setup(Level.Trial)
    public void setup() {
      queue = new ResizableCapacityBlockingQueue<>(tasks);
      values = new FutureTask[tasks];
      for (int i = 0; i < tasks; i++) {
        values[i] = new FutureTask<>(() -> 42);
        if (i % 2 == 0) values[i].cancel(false);
      }
    }

    @Setup(Level.Invocation)
    public void fill() {
      queue.clear();
      for (var value : values) queue.add(value);
    }
  }

  @Benchmark
  public int individualRemoval(QueueState state) {
    int removed = 0;
    for (var task : state.queue) if (task.isCancelled() && state.queue.remove(task)) removed++;
    return removed;
  }

  @Benchmark
  public int bulkRemoval(QueueState state) {
    return state.queue.extractMatching(FutureTask::isCancelled).size();
  }
}
