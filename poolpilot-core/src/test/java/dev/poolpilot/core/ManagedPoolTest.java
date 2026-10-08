package dev.poolpilot.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;

@Timeout(8)
class ManagedPoolTest {
  private final ManagedPoolRegistry registry = new ManagedPoolRegistry(0);

  @AfterEach
  void close() {
    registry.close();
  }

  private ManagedThreadPool pool(PoolSettings s) {
    return registry.register("test", s, TaskDecorator.identity());
  }

  private PoolSettings settings(int core, int max, int capacity, RejectionPolicy policy) {
    return new PoolSettings(core, max, capacity, 60, false, policy, 1000);
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
    assertTrue(condition.getAsBoolean(), "observable condition did not settle");
  }

  @Test
  void shrinkDoesNotLoseQueuedFutures() throws Exception {
    var p = pool(settings(1, 1, 4, RejectionPolicy.ABORT));
    var release = new CountDownLatch(1);
    var started = new CountDownLatch(1);
    Future<Integer> first =
        p.submit(
            () -> {
              started.countDown();
              release.await();
              return -1;
            });
    assertTrue(started.await(1, TimeUnit.SECONDS));
    List<Future<Integer>> queued = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      int n = i;
      queued.add(p.submit(() -> n));
    }
    var next =
        registry.reconfigure("test", settings(1, 1, 1, RejectionPolicy.ABORT), 1, "缩小队列", "test");
    assertEquals(4, next.queueSize());
    assertEquals(0, next.queueRemainingCapacity());
    assertThrows(RejectedExecutionException.class, () -> p.submit(() -> 9));
    release.countDown();
    assertEquals(-1, first.get(1, TimeUnit.SECONDS));
    for (int i = 0; i < 4; i++) assertEquals(i, queued.get(i).get(1, TimeUnit.SECONDS));
    await(() -> p.snapshot().tasks().completed() == 5);
    assertEquals(1, p.snapshot().tasks().rejected());
  }

  @Test
  void configurationIncreaseDecreaseAndCoreTimeoutOrderAreLegal() {
    var p = pool(new PoolSettings(4, 8, 10, 1, true, RejectionPolicy.ABORT, 1000));
    registry.reconfigure(
        "test", new PoolSettings(1, 2, 5, 0, false, RejectionPolicy.ABORT, 500), 1, "缩容", "test");
    registry.reconfigure("test", settings(6, 10, 20, RejectionPolicy.ABORT), 2, "扩容", "test");
    assertEquals(6, p.getCorePoolSize());
    assertEquals(10, p.getMaximumPoolSize());
    assertEquals(3, p.snapshot().version());
    assertFalse(p.allowsCoreThreadTimeOut());
    assertEquals(2, registry.changes().size());
  }

  @Test
  void staleVersionAndInvalidReasonDoNotMutate() {
    var p = pool(PoolSettings.defaults());
    assertThrows(
        VersionConflictException.class,
        () ->
            registry.reconfigure(
                "test", settings(1, 2, 4, RejectionPolicy.ABORT), 0, "冲突", "test"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            registry.reconfigure("test", settings(1, 2, 4, RejectionPolicy.ABORT), 1, " ", "test"));
    assertEquals(1, p.snapshot().version());
    assertTrue(registry.changes().isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () -> new PoolSettings(5, 2, 1, 0, false, RejectionPolicy.ABORT, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PoolSettings(1, 2, 1, 0, true, RejectionPolicy.ABORT, 1));
  }

  @Test
  void exactlyOneConcurrentWriterSucceeds() throws Exception {
    var p = pool(PoolSettings.defaults());
    var writers = Executors.newFixedThreadPool(2);
    var start = new CountDownLatch(1);
    var success = new AtomicInteger();
    try {
      Callable<Void> writer =
          () -> {
            start.await();
            try {
              registry.reconfigure(
                  "test", settings(1, 2, 4, RejectionPolicy.ABORT), 1, "并发修改", "test");
              success.incrementAndGet();
            } catch (VersionConflictException expected) {
            }
            return null;
          };
      var a = writers.submit(writer);
      var b = writers.submit(writer);
      start.countDown();
      a.get(2, TimeUnit.SECONDS);
      b.get(2, TimeUnit.SECONDS);
      assertEquals(1, success.get());
      assertEquals(2, p.snapshot().version());
      assertEquals(1, registry.changes().size());
    } finally {
      writers.shutdownNow();
    }
  }

  @Test
  void futureFailureAndObserverFailureAreVisibleWithoutChangingResult() throws Exception {
    var p = pool(PoolSettings.defaults());
    p.addObserver(
        (name, sample) -> {
          throw new IllegalStateException("observer failed");
        });
    var future =
        p.submit(
            () -> {
              throw new IllegalArgumentException("user failure");
            });
    assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
    await(() -> p.snapshot().tasks().observerFailures() == 1);
    assertEquals(1, p.snapshot().tasks().failed());
    assertEquals(1, p.snapshot().tasks().completed());
  }

  @Test
  void callerRunsBackpressuresAndShutdownStillRejects() throws Exception {
    var p = pool(settings(1, 1, 1, RejectionPolicy.CALLER_RUNS));
    var release = new CountDownLatch(1);
    var started = new CountDownLatch(1);
    p.submit(
        () -> {
          started.countDown();
          release.await();
          return null;
        });
    assertTrue(started.await(1, TimeUnit.SECONDS));
    p.submit(() -> 1);
    var third = p.submit(Thread::currentThread);
    assertSame(Thread.currentThread(), third.get());
    assertEquals(1, p.snapshot().tasks().callerRuns());
    assertEquals(1, p.snapshot().tasks().rejected());
    p.shutdown();
    assertThrows(RejectedExecutionException.class, () -> p.submit(() -> 1));
    release.countDown();
  }

  @Test
  void forceShutdownCancelsQueuedFutures() throws Exception {
    var p = pool(settings(1, 1, 2, RejectionPolicy.ABORT));
    var started = new CountDownLatch(1);
    p.submit(
        () -> {
          started.countDown();
          new CountDownLatch(1).await();
          return null;
        });
    assertTrue(started.await(1, TimeUnit.SECONDS));
    Future<Integer> queued = p.submit(() -> 42);
    p.shutdownNow();
    assertTrue(queued.isCancelled());
    assertThrows(CancellationException.class, queued::get);
    assertTrue(p.awaitTermination(2, TimeUnit.SECONDS));
    assertEquals(1, p.snapshot().tasks().cancelled());
  }

  @Test
  void purgeCancelsQueueAccountingOnceAndRestoresCapacity() throws Exception {
    var p = pool(settings(1, 1, 1, RejectionPolicy.ABORT));
    var release = new CountDownLatch(1);
    var started = new CountDownLatch(1);
    p.submit(
        () -> {
          started.countDown();
          release.await();
          return null;
        });
    assertTrue(started.await(1, TimeUnit.SECONDS));
    var queued = p.submit(() -> 42);
    queued.cancel(false);
    p.purge();
    assertEquals(0, p.getQueue().size());
    assertEquals(1, p.snapshot().tasks().cancelled());
    p.purge();
    assertEquals(1, p.snapshot().tasks().cancelled());
    release.countDown();
  }

  @Test
  void percentileWindowIsBounded() throws Exception {
    var p = pool(settings(1, 1, 512, RejectionPolicy.ABORT));
    List<Future<?>> results = new ArrayList<>();
    for (int i = 0; i < 300; i++) results.add(p.submit(() -> 1));
    for (var f : results) f.get(2, TimeUnit.SECONDS);
    await(() -> p.snapshot().tasks().completed() == 300);
    assertEquals(256, p.snapshot().tasks().windowSamples());
    assertTrue(p.snapshot().tasks().executionP99Millis() >= 0);
  }

  @Test
  void registryLifecycleAndNameValidation() {
    assertThrows(
        IllegalArgumentException.class,
        () -> registry.register("../x", PoolSettings.defaults(), TaskDecorator.identity()));
    pool(PoolSettings.defaults());
    assertThrows(IllegalArgumentException.class, () -> pool(PoolSettings.defaults()));
    registry.close();
    assertThrows(
        IllegalStateException.class,
        () -> registry.register("next", PoolSettings.defaults(), TaskDecorator.identity()));
  }

  @Test
  void removeFindsOriginalCancelledFutureAndAccountsOnlyOnce() throws Exception {
    var p = pool(settings(1, 1, 2, RejectionPolicy.ABORT));
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    p.submit(
        () -> {
          started.countDown();
          release.await();
          return null;
        });
    assertTrue(started.await(1, TimeUnit.SECONDS));
    var queued = p.submit(() -> 42);
    queued.cancel(false);
    assertTrue(p.remove((Runnable) queued));
    assertFalse(p.remove((Runnable) queued));
    assertEquals(0, p.getQueue().size());
    assertEquals(1, p.snapshot().tasks().cancelled());
    release.countDown();
  }

  @Test
  void decoratorFailureDoesNotLeaveAnUnfinishedFuture() throws Exception {
    var failed = new CountDownLatch(1);
    var p =
        registry.register(
            "decorator",
            PoolSettings.defaults(),
            original ->
                () -> {
                  failed.countDown();
                  throw new IllegalStateException("broken decorator");
                });
    p.setThreadFactory(
        task -> {
          Thread thread = new Thread(task);
          thread.setUncaughtExceptionHandler((t, e) -> {});
          return thread;
        });
    var result = p.submit(() -> 42);
    assertTrue(failed.await(1, TimeUnit.SECONDS));
    await(result::isDone);
    assertThrows(CancellationException.class, () -> result.get(1, TimeUnit.SECONDS));
    await(() -> p.snapshot().tasks().failed() == 1);
  }

  @Test
  void explicitAbortSubmissionNeverRunsOnCallerAfterPolicyChange() throws Exception {
    var p = pool(settings(1, 1, 1, RejectionPolicy.ABORT));
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    p.submit(
        () -> {
          started.countDown();
          release.await();
          return null;
        });
    assertTrue(started.await(1, TimeUnit.SECONDS));
    p.submit(() -> 1);
    registry.reconfigure(
        "test", settings(1, 1, 1, RejectionPolicy.CALLER_RUNS), 1, "policy", "test");
    var ran = new AtomicBoolean();
    assertThrows(
        RejectedExecutionException.class,
        () ->
            p.submitOrReject(
                () -> {
                  ran.set(true);
                  return 2;
                }));
    assertFalse(ran.get());
    assertEquals(0, p.snapshot().tasks().callerRuns());
    assertEquals(1, p.snapshot().tasks().rejected());
    release.countDown();
  }

  @Test
  void eventLogRetainsNewestWithinLimit() {
    var log = new BoundedEventLog<Integer>(2);
    log.add(1);
    log.add(2);
    log.add(3);
    assertEquals(List.of(3, 2), log.newestFirst());
    assertThrows(UnsupportedOperationException.class, () -> log.newestFirst().add(4));
  }
}
