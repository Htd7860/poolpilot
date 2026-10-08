package dev.poolpilot.spring;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.poolpilot.core.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Timeout(12)
class ConfigSourceTest {
  @TempDir Path directory;

  private PoolConfigDocument document(long revision, long version) {
    return new PoolConfigDocument(
        1,
        "orders-app",
        revision,
        List.of(
            new PoolConfigDocument.Update(
                "orders", version, "source revision " + revision, PoolSettings.defaults())));
  }

  @Test
  void twoInstancesReconnectWithoutOverwritingLocalChangesAndRestoreRevisionCursor()
      throws Exception {
    var current = new AtomicReference<>(document(10, 1));
    var offline = new AtomicBoolean();
    PoolConfigSource source =
        new PoolConfigSource() {
          public String id() {
            return "nacos";
          }

          public PoolConfigDocument fetch() {
            if (offline.get()) throw new IllegalStateException("offline");
            return current.get();
          }
        };
    Path path = directory.resolve("state.json");
    try (var first = new ManagedPoolRegistry(0, new FilePoolStateStore(path));
        var second = new ManagedPoolRegistry(0)) {
      first.register("orders", PoolSettings.defaults(), TaskDecorator.identity());
      second.register("orders", PoolSettings.defaults(), TaskDecorator.identity());
      try (var a = new PoolConfigRefresher(first, List.of(source), "orders-app", "a", 250);
          var b = new PoolConfigRefresher(second, List.of(source), "orders-app", "b", 250)) {
        a.pollOnce();
        b.pollOnce();
        assertEquals(2, first.get("orders").configurationVersion());
        assertEquals(2, second.get("orders").configurationVersion());
        second.reconfigure("orders", PoolSettings.defaults(), 2, "local override", "alice");
        offline.set(true);
        a.pollOnce();
        b.pollOnce();
        assertEquals(1, a.status().failures());
        offline.set(false);
        current.set(document(11, 2));
        a.pollOnce();
        b.pollOnce();
        assertEquals(3, first.get("orders").configurationVersion());
        assertEquals(3, second.get("orders").configurationVersion());
        assertEquals(1, b.status().conflicts());
        assertEquals(Map.of("nacos", 10L), second.sourceRevisions("orders"));
        current.set(document(12, 3));
        a.pollOnce();
        b.pollOnce();
        assertEquals(4, first.get("orders").configurationVersion());
        assertEquals(4, second.get("orders").configurationVersion());
      }
    }
    try (var restored = new ManagedPoolRegistry(0, new FilePoolStateStore(path))) {
      restored.register("orders", PoolSettings.defaults(), TaskDecorator.identity());
      try (var refresher =
          new PoolConfigRefresher(restored, List.of(source), "orders-app", "c", 250)) {
        refresher.pollOnce();
        assertEquals(1, refresher.status().stale());
        assertEquals(4, restored.get("orders").configurationVersion());
      }
    }
  }

  @Test
  void httpDocumentsAndNacosEnvelopesAreStrictBoundedAndUseConfiguredAuthentication()
      throws Exception {
    var body = new AtomicReference<>(new ObjectMapper().writeValueAsBytes(document(10, 1)));
    var status = new AtomicInteger(200);
    var token = new AtomicReference<String>();
    var nacosToken = new AtomicReference<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/config",
        exchange -> {
          token.set(exchange.getRequestHeaders().getFirst("Authorization"));
          nacosToken.set(exchange.getRequestHeaders().getFirst("accessToken"));
          byte[] bytes = body.get();
          exchange.sendResponseHeaders(status.get(), bytes.length);
          try (var out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();
    URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/config");
    var mapper = new ObjectMapper();
    try (var source =
        new HttpPoolConfigSource("http", uri, "test-source-token", Duration.ofSeconds(2))) {
      assertEquals(10, source.fetch().revision());
      assertEquals("Bearer test-source-token", token.get());
      String valid = mapper.writeValueAsString(document(10, 1));
      for (String invalid :
          List.of(
              valid.replace("\"revision\":10", "\"revision\":\"10\""),
              valid.replace("\"revision\":10", "\"revision\":10.5"),
              valid.replace("\"allowCoreTimeout\":false", "\"allowCoreTimeout\":\"false\""),
              valid.replace("\"revision\":10", "\"revision\":null"),
              valid + " {}")) {
        body.set(invalid.getBytes(StandardCharsets.UTF_8));
        assertThrows(Exception.class, source::fetch);
      }
      body.set("{}".getBytes(StandardCharsets.UTF_8));
      assertThrows(Exception.class, source::fetch);
      body.set(" ".repeat(1048577).getBytes(StandardCharsets.UTF_8));
      assertThrows(Exception.class, source::fetch);
      status.set(503);
      body.set("unavailable".getBytes(StandardCharsets.UTF_8));
      assertThrows(Exception.class, source::fetch);
      status.set(200);
      var text = mapper.writeValueAsString(document(12, 2));
      body.set(mapper.writeValueAsBytes(Map.of("code", 0, "data", text)));
      try (var v2 =
          new HttpPoolConfigSource(
              "nacos",
              uri,
              null,
              Duration.ofSeconds(2),
              HttpPoolConfigSource.Format.NACOS_V2,
              "nacos-access-token")) {
        assertEquals(12, v2.fetch().revision());
        assertEquals("nacos-access-token", nacosToken.get());
      }
      body.set(mapper.writeValueAsBytes(Map.of("code", 0, "data", Map.of("content", text))));
      try (var v3 =
          new HttpPoolConfigSource(
              "nacos",
              uri,
              null,
              Duration.ofSeconds(2),
              HttpPoolConfigSource.Format.NACOS_V3,
              null)) {
        assertEquals(12, v3.fetch().revision());
      }
    } finally {
      server.stop(0);
    }
  }

  @Test
  void stalledResponseBodyTimesOutAndLegacyFileLoadsWithoutDiscardingHistory() throws Exception {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var workers = Executors.newCachedThreadPool();
    server.setExecutor(workers);
    server.createContext(
        "/slow",
        exchange -> {
          exchange.sendResponseHeaders(200, 1000);
          started.countDown();
          try {
            release.await();
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          }
          exchange.close();
        });
    server.start();
    try (var source =
        new HttpPoolConfigSource(
            "slow",
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/slow"),
            null,
            Duration.ofMillis(200))) {
      assertTimeoutPreemptively(
          Duration.ofSeconds(2), () -> assertThrows(Exception.class, source::fetch));
      assertTrue(started.await(1, TimeUnit.SECONDS));
    } finally {
      release.countDown();
      server.stop(0);
      workers.shutdownNow();
    }
    var legacy =
        new ObjectMapper()
            .valueToTree(
                new RegistryState(
                    1,
                    Map.of("orders", new RegistryState.SavedPool(1, PoolSettings.defaults())),
                    List.of()));
    ((com.fasterxml.jackson.databind.node.ObjectNode) legacy.path("pools").path("orders"))
        .remove("sourceRevisions");
    Path file = directory.resolve("legacy.json");
    Files.writeString(file, legacy.toString());
    try (var registry = new ManagedPoolRegistry(0, new FilePoolStateStore(file))) {
      registry.register("orders", PoolSettings.defaults(), TaskDecorator.identity());
      assertTrue(registry.sourceRevisions("orders").isEmpty());
      registry.applyExternal(
          "nacos", 1, "orders", PoolSettings.defaults(), 1, "first source revision");
    }
    try (var registry = new ManagedPoolRegistry(0, new FilePoolStateStore(file))) {
      registry.register("orders", PoolSettings.defaults(), TaskDecorator.identity());
      assertEquals(Map.of("nacos", 1L), registry.sourceRevisions("orders"));
    }
  }
}
