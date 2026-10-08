package dev.poolpilot.spring;

import static org.junit.jupiter.api.Assertions.*;

import dev.poolpilot.core.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;

@Timeout(8)
class TimeoutMonitorTest {
  @Test
  void unfinishedTaskAlertsWhileStateStorageIsBlockedAndCloseClearsTracking() throws Exception {
    var saveEntered = new CountDownLatch(1);
    var releaseSave = new CountDownLatch(1);
    var releaseTask = new CountDownLatch(1);
    var started = new CountDownLatch(1);
    var delivered = new CountDownLatch(1);
    var event = new AtomicReference<PoolAlert>();
    var writer = Executors.newSingleThreadExecutor();
    PoolStateStore store =
        new PoolStateStore() {
          public RegistryState load() {
            return RegistryState.empty();
          }

          public void save(RegistryState state) {
            saveEntered.countDown();
            try {
              releaseSave.await();
            } catch (InterruptedException ex) {
              Thread.currentThread().interrupt();
              throw new StateStoreException("interrupted", ex);
            }
          }
        };
    try (var registry = new ManagedPoolRegistry(0, store)) {
      var pool = registry.register("orders", PoolSettings.defaults(), TaskDecorator.identity());
      try (var alerts =
          new PoolAlertMonitor(
              registry,
              new PoolPilotProperties.Alerts(),
              List.of(
                  a -> {
                    event.set(a);
                    delivered.countDown();
                  }))) {
        var settings = new PoolPilotProperties.Timeouts();
        settings.setQueueMillis(0);
        settings.setRunMillis(1);
        settings.setIntervalMillis(25);
        try (var monitor = new TaskTimeoutMonitor(registry, alerts, settings)) {
          var running =
              pool.submit(
                  () -> {
                    started.countDown();
                    releaseTask.await();
                    return 42;
                  });
          assertTrue(started.await(1, TimeUnit.SECONDS));
          var change =
              writer.submit(
                  () ->
                      registry.reconfigure(
                          "orders", PoolSettings.defaults(), 1, "save", "operator"));
          try {
            assertTrue(saveEntered.await(1, TimeUnit.SECONDS));
            monitor.start();
            assertTrue(delivered.await(1, TimeUnit.SECONDS));
            assertEquals(PoolAlert.Kind.RUN_TIMEOUT, event.get().kind());
            assertEquals(1, event.get().task().thresholdMillis());
            assertNotNull(event.get().task().threadName());
            assertFalse(running.isDone());
            releaseSave.countDown();
            assertEquals(2, change.get(1, TimeUnit.SECONDS).version());
            monitor.close();
            assertEquals(0, pool.trackedTaskCount());
          } finally {
            releaseSave.countDown();
            releaseTask.countDown();
          }
          assertEquals(2, change.get(1, TimeUnit.SECONDS).version());
        }
      }
    } finally {
      releaseSave.countDown();
      releaseTask.countDown();
      writer.shutdownNow();
    }
  }
}
