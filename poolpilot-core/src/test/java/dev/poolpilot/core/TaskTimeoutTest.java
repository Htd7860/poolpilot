package dev.poolpilot.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

@Timeout(8)
class TaskTimeoutTest {
  private ManagedThreadPool pool(int capacity) {
    return new ManagedThreadPool(
        "timeouts",
        new PoolSettings(1, 1, capacity, 60, false, RejectionPolicy.ABORT, 1000),
        TaskDecorator.identity());
  }

  @Test
  void reportsUnfinishedQueuedAndRunningTasksOncePerPhaseWithoutInterrupting() throws Exception {
    var pool = pool(8);
    pool.setTimeoutPolicy(new TaskTimeoutPolicy(10, 10, 8));
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try {
      var running =
          pool.submit(
              () -> {
                started.countDown();
                release.await();
                return Thread.currentThread().isInterrupted();
              });
      assertTrue(started.await(1, TimeUnit.SECONDS));
      var queued = pool.submit(() -> 42);
      long futureTime = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
      var events = pool.taskTimeouts(futureTime);
      assertEquals(2, events.size());
      assertEquals(
          Set.of(TaskTimeoutEvent.Kind.QUEUE, TaskTimeoutEvent.Kind.RUN),
          new HashSet<>(events.stream().map(TaskTimeoutEvent::kind).toList()));
      var run =
          events.stream()
              .filter(e -> e.kind() == TaskTimeoutEvent.Kind.RUN)
              .findFirst()
              .orElseThrow();
      assertTrue(run.threadName().startsWith("poolpilot-timeouts"));
      assertEquals(1, run.configVersion());
      assertFalse(running.isDone());
      assertFalse(queued.isDone());
      assertTrue(pool.taskTimeouts(futureTime).isEmpty());
      assertEquals(1, pool.taskCount(TaskCounter.QUEUE_TIMEOUTS));
      assertEquals(1, pool.taskCount(TaskCounter.RUN_TIMEOUTS));
      queued.cancel(false);
      pool.purge();
      assertEquals(1, pool.trackedTaskCount());
      release.countDown();
      assertFalse(running.get(1, TimeUnit.SECONDS));
      pool.shutdown();
      assertTrue(pool.awaitTermination(1, TimeUnit.SECONDS));
      assertEquals(0, pool.trackedTaskCount());
      assertTrue(pool.taskTimeouts(futureTime).isEmpty());
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void trackingLimitRejectCancellationAndShutdownReleaseRegistrations() throws Exception {
    var pool = pool(1);
    pool.setTimeoutPolicy(new TaskTimeoutPolicy(10, 10, 1));
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try {
      pool.submit(
          () -> {
            started.countDown();
            release.await();
            return 1;
          });
      assertTrue(started.await(1, TimeUnit.SECONDS));
      var queued = pool.submit(() -> 42);
      assertThrows(RejectedExecutionException.class, () -> pool.submit(() -> 7));
      assertEquals(1, pool.trackedTaskCount());
      assertEquals(2, pool.taskCount(TaskCounter.TRACKING_DROPPED));
      var pending = pool.shutdownNow();
      assertEquals(1, pending.size());
      assertTrue(queued.isCancelled());
      assertTrue(pool.awaitTermination(1, TimeUnit.SECONDS));
      assertEquals(0, pool.trackedTaskCount());
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void rejectedTrackedTaskAndExplicitRemovalDoNotLeak() throws Exception {
    var pool = pool(1);
    pool.setTimeoutPolicy(new TaskTimeoutPolicy(10, 10, 8));
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try {
      pool.submit(
          () -> {
            started.countDown();
            release.await();
            return 1;
          });
      assertTrue(started.await(1, TimeUnit.SECONDS));
      var queued = (RunnableFuture<?>) pool.submit(() -> 42);
      assertThrows(RejectedExecutionException.class, () -> pool.submit(() -> 7));
      assertEquals(2, pool.trackedTaskCount());
      assertTrue(pool.remove(queued));
      assertEquals(1, pool.trackedTaskCount());
      queued.cancel(false);
      pool.clearTimeoutTracking();
      assertEquals(0, pool.trackedTaskCount());
      assertTrue(pool.taskTimeouts(System.nanoTime() + TimeUnit.SECONDS.toNanos(1)).isEmpty());
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void bulkPurgePreservesSurvivorOrderAndWakesBlockedProducer() throws Exception {
    var queue = new ResizableCapacityBlockingQueue<Integer>(6);
    for (int i = 0; i < 6; i++) queue.add(i);
    var worker = Executors.newSingleThreadExecutor();
    var producer = new java.util.concurrent.atomic.AtomicReference<Thread>();
    var entered = new CountDownLatch(1);
    try {
      var waiting =
          worker.submit(
              () -> {
                producer.set(Thread.currentThread());
                entered.countDown();
                queue.put(6);
                return 1;
              });
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
      while (producer.get().getState() != Thread.State.WAITING && System.nanoTime() < deadline)
        Thread.sleep(1);
      assertEquals(Thread.State.WAITING, producer.get().getState());
      assertFalse(waiting.isDone());
      var removed = queue.extractMatching(i -> i % 2 == 0);
      assertEquals(List.of(0, 2, 4), removed);
      assertEquals(1, waiting.get(1, TimeUnit.SECONDS));
      assertEquals(List.of(1, 3, 5, 6), new ArrayList<>(queue));
      assertEquals(2, queue.remainingCapacity());
    } finally {
      worker.shutdownNow();
    }
  }
}
