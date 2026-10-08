package dev.poolpilot.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import dev.poolpilot.core.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@Timeout(10)
class PersistenceTest {
  @TempDir Path temporary;

  private PoolSettings capacity(int value) {
    return new PoolSettings(1, 2, value, 60, false, RejectionPolicy.ABORT, 1000);
  }

  @Test
  void atomicReplacementCanBeReadByNewRegistryAndRollbackSurvivesAnotherRestart() throws Exception {
    Path path = temporary.resolve("state.json");
    try (var registry = new ManagedPoolRegistry(0, new FilePoolStateStore(path))) {
      registry.register("orders", capacity(8), TaskDecorator.identity());
      registry.reconfigure("orders", capacity(16), 1, "resize", "operator");
      registry.reconfigure("orders", capacity(24), 2, "second resize", "operator");
    }
    try (var registry = new ManagedPoolRegistry(0, new FilePoolStateStore(path))) {
      var pool = registry.register("orders", capacity(99), TaskDecorator.identity());
      assertEquals(3, pool.snapshot().version());
      assertEquals(24, pool.snapshot().settings().queueCapacity());
      registry.rollback("orders", 1, 3, "restore original", "operator");
    }
    try (var registry = new ManagedPoolRegistry(0, new FilePoolStateStore(path))) {
      var pool = registry.register("orders", capacity(99), TaskDecorator.identity());
      assertEquals(4, pool.snapshot().version());
      assertEquals(8, pool.snapshot().settings().queueCapacity());
      assertEquals(1L, registry.changes().get(0).sourceChangeId());
      assertEquals(3, registry.changes().size());
    }
    try (var files = Files.list(temporary)) {
      assertFalse(files.anyMatch(p -> p.getFileName().toString().endsWith(".tmp")));
    }
  }

  @Test
  void exclusiveFileLockIsReleasedOnCloseAndOnFailedStartup() throws Exception {
    Path path = temporary.resolve("state.json");
    try (var store = new FilePoolStateStore(path)) {
      assertThrows(StateStoreException.class, () -> new FilePoolStateStore(path));
    }
    Files.writeString(path, "corrupt");
    assertThrows(
        StateStoreException.class, () -> new ManagedPoolRegistry(0, new FilePoolStateStore(path)));
    try (var store = new FilePoolStateStore(path)) {
      assertThrows(StateStoreException.class, store::load);
    }
  }

  @Test
  void malformedOversizedAndUnsupportedDocumentsFailClosed() throws Exception {
    Path path = temporary.resolve("state.json");
    for (String invalid :
        java.util.List.of(
            "{",
            "{\"schemaVersion\":2,\"pools\":{},\"changes\":[]}",
            "{\"schemaVersion\":1.5,\"pools\":{},\"changes\":[]}",
            "{\"schemaVersion\":1,\"pools\":{},\"changes\":[]} {}",
            " ".repeat(1_048_577))) {
      Files.writeString(path, invalid);
      try (var store = new FilePoolStateStore(path)) {
        assertThrows(StateStoreException.class, store::load);
      }
      assertEquals(invalid, Files.readString(path));
    }
  }

  @Test
  void springContextsRestoreStateAndFailStartupRatherThanSilentlyResetCorruptData()
      throws Exception {
    Path path = temporary.resolve("state.json");
    var runner =
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PoolPilotAutoConfiguration.class))
            .withPropertyValues(
                "poolpilot.alerts.enabled=false",
                "poolpilot.persistence.enabled=true",
                "poolpilot.persistence.path=" + path,
                "poolpilot.pools[0].name=orders",
                "poolpilot.pools[0].queue-capacity=8");
    runner.run(
        c -> {
          assertThat(c).hasNotFailed();
          var registry = c.getBean(ManagedPoolRegistry.class);
          registry.reconfigure("orders", capacity(16), 1, "resize", "operator");
        });
    runner.run(
        c -> {
          assertThat(c).hasNotFailed();
          var registry = c.getBean(ManagedPoolRegistry.class);
          assertTrue(registry.persistenceEnabled());
          assertEquals(16, registry.get("orders").snapshot().settings().queueCapacity());
          assertEquals(2, registry.get("orders").snapshot().version());
          assertEquals(1, registry.changes().size());
        });
    Files.writeString(path, "broken");
    runner.run(c -> assertThat(c).hasFailed());
    try (var store = new FilePoolStateStore(path)) {
      assertThrows(StateStoreException.class, store::load);
    }
  }
}
