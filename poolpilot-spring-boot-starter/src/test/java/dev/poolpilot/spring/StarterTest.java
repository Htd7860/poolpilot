package dev.poolpilot.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import dev.poolpilot.core.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.*;

@Timeout(8)
class StarterTest {
  @AfterEach
  void cleanup() {
    MDC.clear();
  }

  @Test
  void propertiesCreatePoolsAndDisabledSwitchRemovesRegistry() {
    var runner =
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PoolPilotAutoConfiguration.class));
    runner
        .withPropertyValues(
            "poolpilot.pools[0].name=orders",
            "poolpilot.pools[0].core-size=1",
            "poolpilot.pools[0].max-size=2")
        .run(
            c -> {
              assertThat(c).hasSingleBean(ManagedPoolRegistry.class);
              assertEquals(1, c.getBean(ManagedPoolRegistry.class).get("orders").getCorePoolSize());
            });
    runner
        .withPropertyValues("poolpilot.enabled=false")
        .run(c -> assertThat(c).doesNotHaveBean(ManagedPoolRegistry.class));
  }

  @Test
  void mdcPropagationRestoresPriorContextEvenOnFailure() {
    MDC.put("traceId", "request");
    Runnable task =
        new MdcTaskDecorator()
            .decorate(
                () -> {
                  assertEquals("request", MDC.get("traceId"));
                  throw new IllegalStateException();
                });
    MDC.put("traceId", "worker");
    assertThrows(IllegalStateException.class, task::run);
    assertEquals("worker", MDC.get("traceId"));
  }

  @Test
  void reusedWorkerDoesNotLeakRequestContext() throws Exception {
    try (var registry = new ManagedPoolRegistry(1)) {
      var pool =
          registry.register(
              "mdc",
              new PoolSettings(1, 1, 8, 60, false, RejectionPolicy.ABORT, 1000),
              new MdcTaskDecorator());
      MDC.put("traceId", "first");
      assertEquals("first", pool.submit(() -> MDC.get("traceId")).get(1, TimeUnit.SECONDS));
      MDC.clear();
      assertNull(pool.submit(() -> MDC.get("traceId")).get(1, TimeUnit.SECONDS));
    }
  }

  @Test
  void alertCooldownIsIndependentPerPoolAndType() throws Exception {
    try (var registry = new ManagedPoolRegistry(0)) {
      var pool =
          registry.register(
              "orders",
              new PoolSettings(1, 1, 1, 60, false, RejectionPolicy.ABORT, 1000),
              TaskDecorator.identity());
      var started = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      pool.submit(
          () -> {
            started.countDown();
            release.await();
            return null;
          });
      assertTrue(started.await(1, TimeUnit.SECONDS));
      pool.submit(() -> 1);
      assertThrows(RejectedExecutionException.class, () -> pool.submit(() -> 2));
      var clock = new AtomicLong();
      var properties = new PoolPilotProperties.Alerts();
      properties.setCooldownSeconds(10);
      try (var monitor =
          new PoolAlertMonitor(
              registry,
              properties,
              List.of(
                  a -> {
                    throw new IllegalStateException("listener");
                  }),
              clock::get)) {
        monitor.pollOnce();
        assertEquals(3, monitor.alerts().size());
        monitor.pollOnce();
        assertEquals(3, monitor.alerts().size());
        clock.set(TimeUnit.SECONDS.toNanos(11));
        monitor.pollOnce();
        assertEquals(5, monitor.alerts().size());
        assertEquals(
            1, monitor.alerts().stream().filter(a -> a.kind() == PoolAlert.Kind.REJECTION).count());
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void runtimeRegistrationBindsMetricsAndCloseStopsObservation() throws Exception {
    var meters = new SimpleMeterRegistry();
    try (var registry = new ManagedPoolRegistry(1)) {
      var binder = new PoolMetricsBinder(registry, meters);
      var pool = registry.register("runtime", PoolSettings.defaults(), TaskDecorator.identity());
      assertNotNull(meters.find("poolpilot.pool.core").tag("pool", "runtime").gauge());
      assertEquals(42, pool.submit(() -> 42).get(1, TimeUnit.SECONDS));
      pool.shutdown();
      assertTrue(pool.awaitTermination(1, TimeUnit.SECONDS));
      assertEquals(1, meters.get("poolpilot.task.wait").tag("pool", "runtime").timer().count());
      binder.close();
      binder.close();
      registry.register("after-close", PoolSettings.defaults(), TaskDecorator.identity());
      assertTrue(meters.getMeters().isEmpty());
    } finally {
      meters.close();
    }
  }

  @Test
  void micrometerReportsRealTaskFailuresAndUnbindsMeters() throws Exception {
    var meters = new SimpleMeterRegistry();
    try (var registry = new ManagedPoolRegistry(1)) {
      var pool = registry.register("metrics", PoolSettings.defaults(), TaskDecorator.identity());
      try (var binder = new PoolMetricsBinder(registry, meters)) {
        assertThrows(
            ExecutionException.class,
            () ->
                pool.submit(
                        () -> {
                          throw new IllegalStateException();
                        })
                    .get(1, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (meters
                    .get("poolpilot.task.execution")
                    .tags("pool", "metrics", "outcome", "failure")
                    .timer()
                    .count()
                < 1
            && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(
            1,
            meters
                .get("poolpilot.task.execution")
                .tags("pool", "metrics", "outcome", "failure")
                .timer()
                .count());
        assertEquals(
            1,
            meters.get("poolpilot.tasks.failed").tag("pool", "metrics").functionCounter().count());
      }
      assertTrue(meters.getMeters().isEmpty());
    } finally {
      meters.close();
    }
  }
}
