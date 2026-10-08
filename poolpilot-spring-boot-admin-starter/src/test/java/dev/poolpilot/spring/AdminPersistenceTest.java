package dev.poolpilot.spring;

import static org.junit.jupiter.api.Assertions.*;

import dev.poolpilot.core.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class AdminPersistenceTest {
  @TempDir Path temporary;

  private PoolSettings capacity(int value) {
    return new PoolSettings(1, 2, value, 60, false, RejectionPolicy.ABORT, 1000);
  }

  @Test
  void diskWriteFailureReturns503WithoutApplyingOrAuditingChange() throws Exception {
    Path path = temporary.resolve("state.json");
    try (var registry = new ManagedPoolRegistry(0, new FilePoolStateStore(path))) {
      var pool = registry.register("orders", capacity(8), TaskDecorator.identity());
      // A non-empty directory at the file destination makes atomic replacement fail.
      Files.createDirectory(path);
      Files.writeString(path.resolve("obstacle"), "keep");
      var properties = new PoolPilotProperties.Alerts();
      try (var monitor = new PoolAlertMonitor(registry, properties, java.util.List.of())) {
        try (var refresher =
            new PoolConfigRefresher(registry, java.util.List.of(), "test", "test", 1000)) {
          var controller = new PoolPilotAdminController(registry, monitor, refresher);
          StateStoreException failure =
              assertThrows(
                  StateStoreException.class,
                  () ->
                      controller.update(
                          "orders",
                          new PoolPilotAdminController.UpdateRequest(1, "resize", capacity(16)),
                          new AdminIdentity("operator", true)));
          var response = controller.storage(failure, java.util.Locale.ENGLISH);
          assertEquals(503, response.getStatusCode().value());
          assertEquals("PERSISTENCE_FAILED", response.getBody().code());
          assertFalse(response.getBody().error().contains(temporary.toString()));
        }
      }
      assertEquals(1, pool.snapshot().version());
      assertEquals(8, pool.snapshot().settings().queueCapacity());
      assertTrue(registry.changes().isEmpty());
    }
    try (var files = Files.list(temporary)) {
      assertFalse(files.anyMatch(p -> p.toString().endsWith(".tmp")));
    }
  }
}
