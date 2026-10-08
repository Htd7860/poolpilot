package dev.poolpilot.demo;

import static org.junit.jupiter.api.Assertions.*;

import dev.poolpilot.core.*;
import dev.poolpilot.spring.PoolPilotAdminController.UpdateRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"poolpilot.admin.token=", "poolpilot.persistence.enabled=false"})
class NamedAdminIntegrationTest {
  @Autowired TestRestTemplate http;
  @Autowired ManagedPoolRegistry registry;

  @DynamicPropertySource
  static void users(DynamicPropertyRegistry properties) {
    for (int i = 0; i < 3; i++) {
      String prefix = "poolpilot.admin.users[" + i + "].";
      String name = i == 0 ? "reader" : i == 1 ? "alice" : "expired";
      properties.add(prefix + "name", () -> name);
      properties.add(prefix + "token-sha256", () -> hash(name + "-integration-token"));
      properties.add(prefix + "role", () -> name.equals("reader") ? "READER" : "OPERATOR");
      if (i == 2) properties.add(prefix + "expires-at", () -> "2000-01-01T00:00:00Z");
    }
  }

  static String hash(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  HttpHeaders headers(String name) {
    var headers = new HttpHeaders();
    headers.set("X-PoolPilot-Token", name + "-integration-token");
    return headers;
  }

  @Test
  void servletFilterEnforcesRoleExpiryAndRecordsIndividualActor() {
    var info =
        http.exchange(
            "/poolpilot/api/info",
            HttpMethod.GET,
            new HttpEntity<>(headers("reader")),
            String.class);
    assertEquals(200, info.getStatusCode().value());
    assertTrue(info.getBody().contains("\"writable\":false"));
    assertFalse(info.getBody().contains("tokenSha256"));
    var before = registry.get("orders").snapshot();
    var request = new UpdateRequest(before.version(), "reviewed capacity", before.settings());
    assertEquals(
        403,
        http.exchange(
                "/poolpilot/api/pools/orders",
                HttpMethod.PUT,
                new HttpEntity<>(request, headers("reader")),
                String.class)
            .getStatusCode()
            .value());
    assertEquals(before.version(), registry.get("orders").configurationVersion());
    assertEquals(
        401,
        http.exchange(
                "/poolpilot/api/info",
                HttpMethod.GET,
                new HttpEntity<>(headers("expired")),
                String.class)
            .getStatusCode()
            .value());
    assertEquals(
        200,
        http.exchange(
                "/poolpilot/api/pools/orders",
                HttpMethod.PUT,
                new HttpEntity<>(request, headers("alice")),
                String.class)
            .getStatusCode()
            .value());
    assertEquals("alice", registry.changes().get(0).actor());
  }
}
