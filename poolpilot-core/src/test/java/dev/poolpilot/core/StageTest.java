package dev.poolpilot.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import org.junit.jupiter.api.*;

@Timeout(8)
class StageTest {
  @Test
  void stageFailureUsesOriginalFutureAndCancellationReclaimsQueuedCapacity() throws Exception {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var registry = new ManagedPoolRegistry(1)) {
      var pool =
          registry.register(
              "stages",
              new PoolSettings(1, 1, 1, 60, false, RejectionPolicy.ABORT, 1000),
              TaskDecorator.identity());
      var run =
          pool.submitStage(
              () -> {
                started.countDown();
                release.await();
                return 1;
              });
      assertTrue(started.await(1, TimeUnit.SECONDS));
      var queued = pool.submitStage(() -> 42);
      assertTrue(queued.cancel(false));
      assertEquals(0, pool.getQueue().size());
      assertEquals(1, pool.taskCount(TaskCounter.CANCELLED));
      var failure =
          pool.submitStage(
              () -> {
                throw new IllegalArgumentException("failed stage");
              });
      release.countDown();
      assertEquals(1, run.get(1, TimeUnit.SECONDS));
      assertEquals(
          "failed stage",
          assertThrows(ExecutionException.class, () -> failure.get(1, TimeUnit.SECONDS))
              .getCause()
              .getMessage());
      pool.shutdown();
      assertTrue(pool.awaitTermination(1, TimeUnit.SECONDS));
      assertEquals(1, pool.taskCount(TaskCounter.FAILED));
      assertEquals(3, pool.taskCount(TaskCounter.COMPLETED));
    } finally {
      release.countDown();
    }
  }
}
