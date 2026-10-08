package dev.poolpilot.benchmark;

import dev.poolpilot.core.ResizableCapacityBlockingQueue;
import java.util.concurrent.*;
import org.openjdk.jmh.annotations.*;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(2)
public class QueueBenchmark {
  @State(Scope.Group)
  public static class Shared {
    @Param({"POOLPILOT", "ARRAY", "LINKED"})
    public String implementation;

    @Param({"64", "1024"})
    public int capacity;

    BlockingQueue<Integer> queue;

    @Setup(Level.Trial)
    public void setup() {
      queue =
          switch (implementation) {
            case "POOLPILOT" -> new ResizableCapacityBlockingQueue<>(capacity);
            case "ARRAY" -> new ArrayBlockingQueue<>(capacity);
            case "LINKED" -> new LinkedBlockingQueue<>(capacity);
            default -> throw new IllegalArgumentException(implementation);
          };
    }
  }

  @AuxCounters(AuxCounters.Type.EVENTS)
  @State(Scope.Thread)
  public static class Outcomes {
    public long accepted, full, received, empty;
  }

  @Benchmark
  @Group("exchange")
  @GroupThreads(4)
  public boolean offer(Shared shared, Outcomes counts) {
    boolean accepted = shared.queue.offer(42);
    if (accepted) counts.accepted++;
    else counts.full++;
    return accepted;
  }

  @Benchmark
  @Group("exchange")
  @GroupThreads(4)
  public Integer poll(Shared shared, Outcomes counts) {
    Integer received = shared.queue.poll();
    if (received == null) counts.empty++;
    else counts.received++;
    return received;
  }
}
