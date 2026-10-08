package dev.poolpilot.spring;

import static org.junit.jupiter.api.Assertions.*;

import dev.poolpilot.core.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;

@Timeout(8)
class AlertDeliveryTest {
  @Test
  void blockedNotificationDoesNotDelaySamplingAndQueueIsBounded() throws Exception {
    var releaseTask = new CountDownLatch(1);
    var releaseListener = new CountDownLatch(1);
    var listenerStarted = new CountDownLatch(1);
    var taskStarted = new CountDownLatch(1);
    var clock = new AtomicLong();
    var settings = new PoolPilotProperties.Alerts();
    settings.setNotificationQueueCapacity(1);
    settings.setNotificationShutdownMillis(50);
    settings.setCooldownSeconds(1);
    try (var registry = new ManagedPoolRegistry(0)) {
      var pool =
          registry.register(
              "blocked",
              new PoolSettings(1, 1, 8, 60, false, RejectionPolicy.ABORT, 1000),
              TaskDecorator.identity());
      pool.submit(
          () -> {
            taskStarted.countDown();
            releaseTask.await();
            return 1;
          });
      assertTrue(taskStarted.await(1, TimeUnit.SECONDS));
      var monitor =
          new PoolAlertMonitor(
              registry,
              settings,
              List.of(
                  alert -> {
                    listenerStarted.countDown();
                    boolean interrupted = false;
                    while (releaseListener.getCount() != 0) {
                      try {
                        releaseListener.await();
                      } catch (InterruptedException ex) {
                        interrupted = true;
                      }
                    }
                    if (interrupted) Thread.currentThread().interrupt();
                  }),
              clock::get);
      try {
        monitor.pollOnce();
        assertTrue(listenerStarted.await(1, TimeUnit.SECONDS));
        clock.set(TimeUnit.SECONDS.toNanos(2));
        assertTimeoutPreemptively(Duration.ofSeconds(1), monitor::pollOnce);
        clock.set(TimeUnit.SECONDS.toNanos(4));
        monitor.pollOnce();
        assertEquals(3, monitor.alerts().size());
        assertEquals(1, monitor.deliveryStats().queued());
        assertEquals(1, monitor.deliveryStats().dropped());
        assertTimeoutPreemptively(Duration.ofMillis(500), monitor::close);
        assertEquals(2, monitor.deliveryStats().dropped());
        assertThrows(IllegalStateException.class, monitor::start);
        monitor.pollOnce();
        assertEquals(3, monitor.alerts().size());
      } finally {
        releaseListener.countDown();
        releaseTask.countDown();
        monitor.close();
      }
    } finally {
      releaseListener.countDown();
      releaseTask.countDown();
    }
  }

  @Test
  void listenerFailuresAreCountedAndDoNotPreventOtherListeners() throws Exception {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var delivered = new CountDownLatch(1);
    try (var registry = new ManagedPoolRegistry(0)) {
      var pool =
          registry.register(
              "failing",
              new PoolSettings(1, 1, 8, 60, false, RejectionPolicy.ABORT, 1000),
              TaskDecorator.identity());
      pool.submit(
          () -> {
            started.countDown();
            release.await();
            return 1;
          });
      assertTrue(started.await(1, TimeUnit.SECONDS));
      try (var monitor =
          new PoolAlertMonitor(
              registry,
              new PoolPilotProperties.Alerts(),
              List.of(
                  a -> {
                    throw new IllegalStateException("notification failed");
                  },
                  a -> delivered.countDown()))) {
        monitor.pollOnce();
        assertTrue(delivered.await(1, TimeUnit.SECONDS));
        assertEquals(1, monitor.deliveryStats().failed());
      } finally {
        release.countDown();
      }
    } finally {
      release.countDown();
    }
  }
}
