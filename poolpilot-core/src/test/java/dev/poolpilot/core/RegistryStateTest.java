package dev.poolpilot.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;

class RegistryStateTest {
  @Test
  void countersRemainReadableWhileConfigurationStorageIsBlocked() throws Exception {
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    PoolStateStore store =
        new PoolStateStore() {
          public RegistryState load() {
            return RegistryState.empty();
          }

          public void save(RegistryState next) {
            entered.countDown();
            try {
              release.await();
            } catch (InterruptedException ex) {
              Thread.currentThread().interrupt();
              throw new StateStoreException("interrupted save", ex);
            }
          }
        };
    var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
    try (var registry = new ManagedPoolRegistry(0, store)) {
      var pool = registry.register("orders", capacity(8), TaskDecorator.identity());
      var completed = new java.util.concurrent.CountDownLatch(1);
      pool.addObserver((name, sample) -> completed.countDown());
      assertEquals(42, pool.submit(() -> 42).get(1, java.util.concurrent.TimeUnit.SECONDS));
      assertTrue(completed.await(1, java.util.concurrent.TimeUnit.SECONDS));
      var change =
          workers.submit(
              () -> registry.reconfigure("orders", capacity(16), 1, "resize", "operator"));
      try {
        assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS));
        var read =
            workers.submit(
                () ->
                    List.of(
                        pool.taskCount(TaskCounter.SUBMITTED),
                        pool.taskCount(TaskCounter.COMPLETED),
                        pool.configurationVersion(),
                        (long) pool.queueCapacity()));
        assertEquals(List.of(1L, 1L, 1L, 8L), read.get(1, java.util.concurrent.TimeUnit.SECONDS));
      } finally {
        release.countDown();
      }
      assertEquals(2, change.get(1, java.util.concurrent.TimeUnit.SECONDS).version());
      assertEquals(16, pool.queueCapacity());
    } finally {
      release.countDown();
      workers.shutdownNow();
    }
  }

  private static final class MemoryStore implements PoolStateStore {
    RegistryState state = RegistryState.empty();
    boolean fail;

    public RegistryState load() {
      return state;
    }

    public void save(RegistryState next) {
      if (fail) throw new StateStoreException("disk unavailable", null);
      state = next;
    }
  }

  private PoolSettings capacity(int value) {
    var d = PoolSettings.defaults();
    return new PoolSettings(
        d.coreSize(),
        d.maxSize(),
        value,
        d.keepAliveSeconds(),
        d.allowCoreTimeout(),
        d.rejectionPolicy(),
        d.slowTaskMillis());
  }

  @Test
  void restartRestoresVersionsHistoryAndOnlyExplicitlyRegisteredPools() {
    var store = new MemoryStore();
    try (var registry = new ManagedPoolRegistry(0, store)) {
      registry.register("orders", capacity(8), TaskDecorator.identity());
      registry.register("removed", capacity(9), TaskDecorator.identity());
      registry.reconfigure("orders", capacity(16), 1, "resize", "operator");
    }
    try (var restarted = new ManagedPoolRegistry(0, store)) {
      assertTrue(restarted.pools().isEmpty());
      var pool = restarted.register("orders", capacity(99), TaskDecorator.identity());
      assertEquals(2, pool.snapshot().version());
      assertEquals(16, pool.snapshot().settings().queueCapacity());
      assertEquals("operator", restarted.changes().get(0).actor());
      assertThrows(
          VersionConflictException.class,
          () -> restarted.reconfigure("orders", capacity(20), 1, "stale", "operator"));
      var restored = restarted.rollback("orders", 1, 2, "undo resize", "operator");
      assertEquals(3, restored.version());
      assertEquals(8, restored.settings().queueCapacity());
      assertEquals(PoolChange.Kind.ROLLBACK, restarted.changes().get(0).kind());
      assertEquals(1L, restarted.changes().get(0).sourceChangeId());
      assertEquals(2, restarted.changes().get(0).id());
      assertFalse(store.state.pools().containsKey("removed"));
    }
  }

  @Test
  void failedWriteLeavesPoolHistoryAndChangeSequenceUnchanged() {
    var store = new MemoryStore();
    try (var registry = new ManagedPoolRegistry(0, store)) {
      var pool = registry.register("orders", capacity(8), TaskDecorator.identity());
      store.fail = true;
      assertThrows(
          StateStoreException.class,
          () -> registry.reconfigure("orders", capacity(16), 1, "resize", "operator"));
      assertEquals(1, pool.snapshot().version());
      assertEquals(8, pool.getQueue().remainingCapacity());
      assertEquals(8, pool.snapshot().settings().queueCapacity());
      assertTrue(registry.changes().isEmpty());
      assertTrue(store.state.changes().isEmpty());
      store.fail = false;
      registry.reconfigure("orders", capacity(16), 1, "resize", "operator");
      store.fail = true;
      assertThrows(
          StateStoreException.class, () -> registry.rollback("orders", 1, 2, "undo", "operator"));
      assertEquals(2, pool.snapshot().version());
      assertEquals(1, registry.changes().size());
      assertEquals(1, registry.changes().get(0).id());
    }
  }

  @Test
  void rollbackChecksCurrentVersionAndPoolOwnership() {
    try (var registry = new ManagedPoolRegistry(0)) {
      registry.register("a", capacity(8), TaskDecorator.identity());
      registry.register("b", capacity(8), TaskDecorator.identity());
      registry.reconfigure("a", capacity(16), 1, "first", "operator");
      registry.reconfigure("a", capacity(32), 2, "second", "operator");
      assertThrows(
          VersionConflictException.class,
          () -> registry.rollback("a", 1, 2, "stale rollback", "operator"));
      assertThrows(
          ChangeNotFoundException.class,
          () -> registry.rollback("b", 1, 1, "wrong pool", "operator"));
      assertThrows(
          ChangeNotFoundException.class,
          () -> registry.rollback("a", 999, 3, "missing", "operator"));
      assertEquals(3, registry.get("a").snapshot().version());
      assertEquals(2, registry.changes().size());
      assertEquals(
          capacity(8),
          registry.rollback("a", 1, 3, "reviewed full restore", "operator").settings());
      assertEquals(4, registry.get("a").snapshot().version());
    }
  }

  @Test
  void retainedHistoryIsBoundedAndExpiredChangesCannotBeRolledBack() {
    var store = new MemoryStore();
    try (var registry = new ManagedPoolRegistry(0, store)) {
      registry.register("orders", capacity(8), TaskDecorator.identity());
      for (int i = 1; i <= 205; i++)
        registry.reconfigure("orders", capacity(8 + i), i, "resize " + i, "operator");
      assertEquals(200, store.state.changes().size());
      assertEquals(205, store.state.changes().get(0).id());
      assertEquals(6, store.state.changes().get(199).id());
      assertThrows(
          ChangeNotFoundException.class,
          () -> registry.rollback("orders", 1, 206, "expired", "operator"));
    }
  }

  @Test
  void rejectsInconsistentStateAndReturnsImmutableCollections() {
    var store = new MemoryStore();
    try (var registry = new ManagedPoolRegistry(0, store)) {
      registry.register("orders", capacity(8), TaskDecorator.identity());
      registry.reconfigure("orders", capacity(16), 1, "resize", "operator");
      assertThrows(IllegalArgumentException.class, () -> new RegistryState(2, Map.of(), List.of()));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new RegistryState(
                  1,
                  Map.of("orders", new RegistryState.SavedPool(3, capacity(16))),
                  store.state.changes()));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new RegistryState(
                  1,
                  store.state.pools(),
                  List.of(store.state.changes().get(0), store.state.changes().get(0))));
      assertThrows(UnsupportedOperationException.class, () -> store.state.changes().clear());
      assertThrows(UnsupportedOperationException.class, () -> store.state.pools().clear());
    }
  }
}
