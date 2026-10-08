package dev.poolpilot.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;

class ExternalConfigTest {
  static final class Store implements PoolStateStore {
    RegistryState state = RegistryState.empty();
    boolean fail;

    public RegistryState load() {
      return state;
    }

    public void save(RegistryState value) {
      if (fail) throw new StateStoreException("save failed", null);
      state = value;
    }
  }

  @Test
  void revisionsSurviveRestartAndLocalWritesProtectAgainstReplayedRemoteUpdates() {
    var store = new Store();
    try (var registry = new ManagedPoolRegistry(0, store)) {
      registry.register("orders", PoolSettings.defaults(), TaskDecorator.identity());
      assertEquals(
          ManagedPoolRegistry.ExternalResult.APPLIED,
          registry.applyExternal("nacos", 10, "orders", PoolSettings.defaults(), 1, "revision 10"));
      assertEquals(PoolChange.Kind.EXTERNAL, registry.changes().get(0).kind());
      assertEquals("config:nacos", registry.changes().get(0).actor());
      registry.reconfigure("orders", PoolSettings.defaults(), 2, "manual correction", "alice");
    }
    try (var registry = new ManagedPoolRegistry(0, store)) {
      var pool = registry.register("orders", PoolSettings.defaults(), TaskDecorator.identity());
      assertEquals(
          ManagedPoolRegistry.ExternalResult.STALE,
          registry.applyExternal("nacos", 9, "orders", PoolSettings.defaults(), 1, "old"));
      assertEquals(
          ManagedPoolRegistry.ExternalResult.STALE,
          registry.applyExternal("nacos", 10, "orders", PoolSettings.defaults(), 2, "duplicate"));
      assertThrows(
          VersionConflictException.class,
          () ->
              registry.applyExternal(
                  "nacos", 11, "orders", PoolSettings.defaults(), 2, "conflicting"));
      assertEquals(3, pool.configurationVersion());
      assertEquals(Map.of("nacos", 10L), registry.sourceRevisions("orders"));
      store.fail = true;
      assertThrows(
          StateStoreException.class,
          () ->
              registry.applyExternal(
                  "nacos", 11, "orders", PoolSettings.defaults(), 3, "save failure"));
      assertEquals(Map.of("nacos", 10L), registry.sourceRevisions("orders"));
      store.fail = false;
      registry.applyExternal("nacos", 11, "orders", PoolSettings.defaults(), 3, "rebased revision");
      assertEquals(4, pool.configurationVersion());
      assertEquals(Map.of("nacos", 11L), registry.sourceRevisions("orders"));
    }
  }
}
