package dev.poolpilot.demo;

import static org.junit.jupiter.api.Assertions.*;

import dev.poolpilot.core.*;
import dev.poolpilot.spring.PoolAlertMonitor;
import dev.poolpilot.spring.PoolPilotAdminController.UpdateRequest;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "poolpilot.admin.token=integration-test-token",
      "poolpilot.persistence.enabled=false"
    })
@AutoConfigureObservability
@Timeout(10)
class AdminIntegrationTest {
  @Autowired TestRestTemplate http;
  @Autowired ManagedPoolRegistry registry;
  @Autowired PoolAlertMonitor monitor;

  @Test
  void publicAuthenticationMetadataContainsOnlyTheMode() {
    var response = http.getForEntity("/poolpilot/api/auth", Map.class);
    assertEquals(200, response.getStatusCode().value());
    assertEquals(Map.of("mode", "TOKEN"), response.getBody());
    assertEquals("no-store", response.getHeaders().getCacheControl());
    assertEquals(
        401, http.getForEntity("/poolpilot/api/info", String.class).getStatusCode().value());
  }

  @Test
  void unfinishedQueueAndRunTimeoutsReachTheAuthenticatedApi() throws Exception {
    var pool =
        registry.register(
            "timeout-api",
            new PoolSettings(1, 1, 8, 60, false, RejectionPolicy.ABORT, 1000),
            TaskDecorator.identity());
    pool.setTimeoutPolicy(new TaskTimeoutPolicy(25, 25, 8));
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try {
      pool.submit(
          () -> {
            entered.countDown();
            release.await();
            return 1;
          });
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      pool.submit(() -> 2);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while ((pool.taskCount(TaskCounter.QUEUE_TIMEOUTS) < 1
              || pool.taskCount(TaskCounter.RUN_TIMEOUTS) < 1)
          && System.nanoTime() < deadline) Thread.sleep(10);
      assertEquals(1, pool.taskCount(TaskCounter.QUEUE_TIMEOUTS));
      assertEquals(1, pool.taskCount(TaskCounter.RUN_TIMEOUTS));
      var response =
          http.exchange(
              "/poolpilot/api/alerts", HttpMethod.GET, new HttpEntity<>(headers()), String.class);
      assertEquals(200, response.getStatusCode().value());
      assertTrue(response.getBody().contains("RUN_TIMEOUT"));
      assertTrue(response.getBody().contains("QUEUE_TIMEOUT"));
      assertTrue(response.getBody().contains("taskId"));
      assertFalse(pool.isShutdown());
    } finally {
      release.countDown();
    }
  }

  @Test
  void rollbackApiAuditsNewVersionAndRejectsStaleOrForeignChange() {
    var before = registry.get("orders").snapshot();
    var d = before.settings();
    var next =
        new PoolSettings(
            d.coreSize(),
            d.maxSize(),
            d.queueCapacity() + 1,
            d.keepAliveSeconds(),
            d.allowCoreTimeout(),
            d.rejectionPolicy(),
            d.slowTaskMillis());
    registry.reconfigure("orders", next, before.version(), "rollback source", "test");
    long id = registry.changes().get(0).id();
    var request =
        new dev.poolpilot.spring.PoolPilotAdminController.RollbackRequest(
            id, before.version() + 1, "undo resize");
    assertEquals(
        401,
        http.postForEntity("/poolpilot/api/pools/orders/rollback", request, String.class)
            .getStatusCode()
            .value());
    var response =
        http.exchange(
            "/poolpilot/api/pools/orders/rollback",
            HttpMethod.POST,
            new HttpEntity<>(request, headers()),
            PoolSnapshot.class);
    assertEquals(200, response.getStatusCode().value());
    assertEquals(d, response.getBody().settings());
    assertEquals(before.version() + 2, response.getBody().version());
    assertEquals(PoolChange.Kind.ROLLBACK, registry.changes().get(0).kind());
    assertEquals(id, registry.changes().get(0).sourceChangeId());
    assertEquals(
        409,
        http.exchange(
                "/poolpilot/api/pools/orders/rollback",
                HttpMethod.POST,
                new HttpEntity<>(request, headers()),
                String.class)
            .getStatusCode()
            .value());
    var foreign =
        new dev.poolpilot.spring.PoolPilotAdminController.RollbackRequest(
            id, registry.get("reports").snapshot().version(), "wrong pool");
    var missing =
        http.exchange(
            "/poolpilot/api/pools/reports/rollback",
            HttpMethod.POST,
            new HttpEntity<>(foreign, headers()),
            String.class);
    assertEquals(404, missing.getStatusCode().value());
    assertTrue(missing.getBody().contains("CHANGE_NOT_FOUND"));
    assertEquals(
        400,
        http.exchange(
                "/poolpilot/api/pools/orders/rollback",
                HttpMethod.POST,
                new HttpEntity<>(
                    new dev.poolpilot.spring.PoolPilotAdminController.RollbackRequest(
                        null, 1L, "bad"),
                    headers()),
                String.class)
            .getStatusCode()
            .value());
  }

  @Test
  void consoleInfoReportsStorageWithoutExposingSecrets() {
    var response =
        http.exchange(
            "/poolpilot/api/info", HttpMethod.GET, new HttpEntity<>(headers()), String.class);
    assertEquals(200, response.getStatusCode().value());
    assertTrue(response.getBody().contains("\"configurationPersistence\":false"));
    assertFalse(response.getBody().contains("integration-test-token"));
  }

  private HttpHeaders headers() {
    var h = new HttpHeaders();
    h.set("X-PoolPilot-Token", "integration-test-token");
    h.set("Accept-Language", "zh-CN");
    return h;
  }

  @Test
  void readAndWriteRequireToken() {
    assertEquals(
        401, http.getForEntity("/poolpilot/api/pools", String.class).getStatusCode().value());
    assertEquals(
        401,
        http.exchange(
                "/poolpilot/api/pools/orders",
                HttpMethod.PUT,
                new HttpEntity<>("{}", new HttpHeaders()),
                String.class)
            .getStatusCode()
            .value());
    var h = headers();
    h.set("X-PoolPilot-Token", "wrong");
    assertEquals(
        401,
        http.exchange("/poolpilot/api/pools", HttpMethod.GET, new HttpEntity<>(h), String.class)
            .getStatusCode()
            .value());
  }

  @Test
  void readsPoolsAndServesDashboard() {
    var response =
        http.exchange(
            "/poolpilot/api/pools", HttpMethod.GET, new HttpEntity<>(headers()), String.class);
    assertEquals(200, response.getStatusCode().value());
    assertTrue(response.getBody().contains("orders"));
    assertTrue(http.getForObject("/poolpilot/index.html", String.class).contains("PoolPilot"));
  }

  @Test
  void mutationReturnsNewVersionAndAuditAndRejectsStaleWriter() {
    var before = registry.get("orders").snapshot();
    var old = before.settings();
    var next =
        new PoolSettings(
            old.coreSize(),
            old.maxSize(),
            old.queueCapacity() + 1,
            old.keepAliveSeconds(),
            old.allowCoreTimeout(),
            old.rejectionPolicy(),
            old.slowTaskMillis());
    var request = new UpdateRequest(before.version(), "integration resize", next);
    var response =
        http.exchange(
            "/poolpilot/api/pools/orders",
            HttpMethod.PUT,
            new HttpEntity<>(request, headers()),
            PoolSnapshot.class);
    assertEquals(200, response.getStatusCode().value());
    assertEquals(before.version() + 1, response.getBody().version());
    var stale =
        http.exchange(
            "/poolpilot/api/pools/orders",
            HttpMethod.PUT,
            new HttpEntity<>(request, headers()),
            String.class);
    assertEquals(409, stale.getStatusCode().value());
    assertTrue(stale.getBody().contains("读取最新配置"));
    assertTrue(
        http.exchange(
                "/poolpilot/api/changes", HttpMethod.GET, new HttpEntity<>(headers()), String.class)
            .getBody()
            .contains("integration resize"));
  }

  @Test
  void rejectsInvalidConfigurationAndMissingReasonWithoutMutation() {
    var before = registry.get("reports").snapshot();
    var h = headers();
    h.setContentType(MediaType.APPLICATION_JSON);
    String invalid =
        "{\"expectedVersion\":"
            + before.version()
            + ",\"reason\":\"bad\",\"settings\":{\"coreSize\":8,\"maxSize\":2,\"queueCapacity\":1,\"keepAliveSeconds\":60,\"allowCoreTimeout\":false,\"rejectionPolicy\":\"ABORT\",\"slowTaskMillis\":1000}}";
    assertEquals(
        400,
        http.exchange(
                "/poolpilot/api/pools/reports",
                HttpMethod.PUT,
                new HttpEntity<>(invalid, h),
                String.class)
            .getStatusCode()
            .value());
    var missing = new UpdateRequest(before.version(), "", before.settings());
    assertEquals(
        400,
        http.exchange(
                "/poolpilot/api/pools/reports",
                HttpMethod.PUT,
                new HttpEntity<>(missing, h),
                String.class)
            .getStatusCode()
            .value());
    for (String incomplete :
        List.of(
            invalid.replace("\"coreSize\":8,", ""),
            invalid.replace("\"coreSize\":8", "\"coreSize\":null"))) {
      assertEquals(
          400,
          http.exchange(
                  "/poolpilot/api/pools/reports",
                  HttpMethod.PUT,
                  new HttpEntity<>(incomplete, h),
                  String.class)
              .getStatusCode()
              .value());
    }
    var missingVersion = Map.of("reason", "missing version", "settings", before.settings());
    assertEquals(
        400,
        http.exchange(
                "/poolpilot/api/pools/reports",
                HttpMethod.PUT,
                new HttpEntity<>(missingVersion, headers()),
                String.class)
            .getStatusCode()
            .value());
    assertEquals(before.version(), registry.get("reports").snapshot().version());
  }

  @Test
  void decimalAndStringNumbersCannotSilentlyChangeConfiguration() {
    var before = registry.get("reports").snapshot();
    var h = headers();
    h.setContentType(MediaType.APPLICATION_JSON);
    String settings =
        "{\"coreSize\":1,\"maxSize\":2,\"queueCapacity\":8,\"keepAliveSeconds\":60,\"allowCoreTimeout\":false,\"rejectionPolicy\":\"ABORT\",\"slowTaskMillis\":1000}";
    for (String invalid :
        List.of(
            settings.replace("\"coreSize\":1", "\"coreSize\":1.5"),
            settings.replace("\"queueCapacity\":8", "\"queueCapacity\":\"8\""),
            settings.replace("\"keepAliveSeconds\":60", "\"keepAliveSeconds\":60.5"))) {
      String body =
          "{\"expectedVersion\":"
              + before.version()
              + ",\"reason\":\"strict numbers\",\"settings\":"
              + invalid
              + "}";
      var response =
          http.exchange(
              "/poolpilot/api/pools/reports", HttpMethod.PUT, new HttpEntity<>(body, h), Map.class);
      assertEquals(400, response.getStatusCode().value());
      assertEquals("INVALID_REQUEST", response.getBody().get("code"));
    }
    assertEquals(before.version(), registry.get("reports").snapshot().version());
    var invalidDemo =
        http.exchange(
            "/poolpilot/api/demo/load",
            HttpMethod.POST,
            new HttpEntity<>(
                "{\"pool\":\"orders\",\"tasks\":1.5,\"durationMillis\":1,\"failEvery\":0}", h),
            Map.class);
    assertEquals(400, invalidDemo.getStatusCode().value());
  }

  @Test
  void unknownPoolReturns404() {
    assertEquals(
        404,
        http.exchange(
                "/poolpilot/api/pools/missing",
                HttpMethod.GET,
                new HttpEntity<>(headers()),
                String.class)
            .getStatusCode()
            .value());
  }

  @Test
  void prometheusIsBoundToGovernedPools() {
    var response = http.getForEntity("/actuator/prometheus", String.class);
    assertEquals(200, response.getStatusCode().value());
    String metrics = response.getBody();
    assertTrue(metrics.contains("poolpilot_pool_core"));
    assertTrue(metrics.contains("pool=\"orders\""));
    assertTrue(metrics.contains("poolpilot_task_execution_seconds"));
  }

  @Test
  void demoLoadCreatesObservableFailures() throws Exception {
    long before = registry.get("orders").snapshot().tasks().failed();
    var load = Map.of("pool", "orders", "tasks", 8, "durationMillis", 10, "failEvery", 2);
    var response =
        http.exchange(
            "/poolpilot/api/demo/load",
            HttpMethod.POST,
            new HttpEntity<>(load, headers()),
            String.class);
    assertEquals(202, response.getStatusCode().value());
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (registry.get("orders").snapshot().tasks().failed() < before + 4
        && System.nanoTime() < deadline) Thread.sleep(10);
    assertTrue(registry.get("orders").snapshot().tasks().failed() >= before + 4);
  }

  @Test
  void alertApiLocalizesMessagesWithoutChangingStructuredValues() throws Exception {
    var pool =
        registry.register(
            "locale-alert",
            new PoolSettings(1, 1, 1, 60, false, RejectionPolicy.ABORT, 1000),
            TaskDecorator.identity());
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try {
      pool.submit(
          () -> {
            started.countDown();
            release.await();
            return null;
          });
      assertTrue(started.await(1, TimeUnit.SECONDS));
      pool.submit(() -> 1);
      assertThrows(RejectedExecutionException.class, () -> pool.submit(() -> 2));
      monitor.pollOnce();
      for (String language : List.of("zh-CN", "en-US")) {
        var h = headers();
        h.set("Accept-Language", language);
        var response =
            http.exchange(
                "/poolpilot/api/alerts", HttpMethod.GET, new HttpEntity<>(h), Map[].class);
        var event =
            Arrays.stream(response.getBody())
                .filter(a -> "locale-alert".equals(a.get("pool")) && "QUEUE".equals(a.get("kind")))
                .findFirst()
                .orElseThrow();
        assertEquals(100, ((Number) event.get("value")).intValue());
        assertEquals(
            language.startsWith("zh") ? "队列占用 100%" : "Queue usage: 100%", event.get("message"));
      }
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void errorsFollowLanguageAndExposeStableCodes() {
    for (var language : List.of("zh-CN", "en-US")) {
      var h = headers();
      h.set("Accept-Language", language);
      h.set("X-PoolPilot-Token", "bad");
      var denied =
          http.exchange("/poolpilot/api/pools", HttpMethod.GET, new HttpEntity<>(h), Map.class);
      assertEquals(401, denied.getStatusCode().value());
      assertEquals("no-store", denied.getHeaders().getFirst("Cache-Control"));
      assertEquals("UNAUTHORIZED", denied.getBody().get("code"));
      assertTrue(
          denied
              .getBody()
              .get("error")
              .toString()
              .contains(language.startsWith("zh") ? "令牌" : "token"));
      h.set("X-PoolPilot-Token", "integration-test-token");
      var invalid =
          http.exchange(
              "/poolpilot/api/pools/reports",
              HttpMethod.PUT,
              new HttpEntity<>(Map.of("reason", "missing settings"), h),
              Map.class);
      assertEquals(400, invalid.getStatusCode().value());
      assertEquals("SETTINGS_REQUIRED", invalid.getBody().get("code"));
      assertTrue(
          invalid
              .getBody()
              .get("error")
              .toString()
              .contains(language.startsWith("zh") ? "参数" : "settings"));
      var missing =
          http.exchange(
              "/poolpilot/api/pools/missing", HttpMethod.GET, new HttpEntity<>(h), Map.class);
      assertEquals("POOL_NOT_FOUND", missing.getBody().get("code"));
      var demo =
          http.exchange(
              "/poolpilot/api/demo/load",
              HttpMethod.POST,
              new HttpEntity<>(Map.of("pool", "orders", "tasks", 201, "durationMillis", 1), h),
              Map.class);
      assertEquals(400, demo.getStatusCode().value());
      assertEquals("LOAD_RANGE", demo.getBody().get("code"));
    }
  }

  @Test
  void versionConflictIsLocalizedAndCarriesActualVersion() {
    var p = registry.get("reports").snapshot();
    var h = headers();
    h.set("Accept-Language", "en-US");
    var response =
        http.exchange(
            "/poolpilot/api/pools/reports",
            HttpMethod.PUT,
            new HttpEntity<>(new UpdateRequest(p.version() + 100, "stale", p.settings()), h),
            Map.class);
    assertEquals(409, response.getStatusCode().value());
    assertEquals("VERSION_CONFLICT", response.getBody().get("code"));
    assertTrue(response.getBody().get("error").toString().contains("Reload"));
    assertEquals(
        p.version(),
        ((Number) ((Map<?, ?>) response.getBody().get("parameters")).get("version")).longValue());
  }

  @Test
  void demoLoadIsBounded() {
    var missingPool = Map.of("tasks", 1, "durationMillis", 10, "failEvery", 0);
    assertEquals(
        400,
        http.exchange(
                "/poolpilot/api/demo/load",
                HttpMethod.POST,
                new HttpEntity<>(missingPool, headers()),
                String.class)
            .getStatusCode()
            .value());
    var load = Map.of("pool", "orders", "tasks", 201, "durationMillis", 10, "failEvery", 0);
    assertEquals(
        400,
        http.exchange(
                "/poolpilot/api/demo/load",
                HttpMethod.POST,
                new HttpEntity<>(load, headers()),
                String.class)
            .getStatusCode()
            .value());
  }
}
