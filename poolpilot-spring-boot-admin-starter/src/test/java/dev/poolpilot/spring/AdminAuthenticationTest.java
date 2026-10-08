package dev.poolpilot.spring;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.poolpilot.core.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdminAuthenticationTest {
  private static final String READER = "reader-token-for-testing",
      WRITER = "writer-token-for-testing";

  private PoolPilotProperties.Admin.User user(
      String name, String token, PoolPilotProperties.Admin.Role role) {
    var user = new PoolPilotProperties.Admin.User();
    user.setName(name);
    user.setTokenSha256(HexFormat.of().formatHex(TokenAdminAuthenticator.hash(token)));
    user.setRole(role);
    return user;
  }

  private MockHttpServletRequest request(String token) {
    var request = new MockHttpServletRequest();
    if (token != null) request.addHeader("X-PoolPilot-Token", token);
    return request;
  }

  @Test
  void authenticationModeIsPublicWithoutExposingCredentialsOrBypassingAuthorization()
      throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    for (var mode : PoolPilotProperties.Admin.Authentication.values()) {
      var properties = new PoolPilotProperties();
      properties.getAdmin().setAuthentication(mode);
      properties.getAdmin().setToken(WRITER);
      var configuration = new PoolPilotAdminAutoConfiguration();
      var registration =
          configuration.poolPilotAdminAuthentication(
              configuration.poolPilotAdminAuthenticator(properties), mapper);
      try (var registry = new ManagedPoolRegistry(0);
          var monitor = new PoolAlertMonitor(registry, properties.getAlerts(), List.of());
          var refresher =
              new PoolConfigRefresher(registry, List.of(), "orders-app", "instance-a", 1000)) {
        var mvc =
            MockMvcBuilders.standaloneSetup(
                    configuration.poolPilotAdminAuthController(properties),
                    new PoolPilotAdminController(registry, monitor, refresher))
                .addFilters(registration.getFilter())
                .build();
        mvc.perform(get("/poolpilot/api/auth"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().json("{\"mode\":\"" + mode + "\"}", true));
        mvc.perform(get("/app/poolpilot/api/auth").contextPath("/app")).andExpect(status().isOk());
        mvc.perform(head("/poolpilot/api/auth")).andExpect(status().isOk());
        mvc.perform(post("/poolpilot/api/auth")).andExpect(status().isUnauthorized());
        mvc.perform(get("/poolpilot/api/auth/extra")).andExpect(status().isUnauthorized());
        mvc.perform(get("/poolpilot/api/info").header("X-PoolPilot-User", "alice"))
            .andExpect(status().isUnauthorized());
        if (mode == PoolPilotProperties.Admin.Authentication.APPLICATION) {
          mvc.perform(get("/poolpilot/api/info").principal(() -> "alice"))
              .andExpect(status().isUnauthorized());
          mvc.perform(
                  get("/poolpilot/api/info")
                      .principal(() -> "alice")
                      .with(
                          request -> {
                            request.addUserRole("POOLPILOT_READ");
                            return request;
                          }))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.user").value("alice"))
              .andExpect(jsonPath("$.writable").value(false));
          mvc.perform(
                  put("/poolpilot/api/pools/orders")
                      .principal(() -> "alice")
                      .with(
                          request -> {
                            request.addUserRole("POOLPILOT_READ");
                            return request;
                          }))
              .andExpect(status().isForbidden());
        }
      }
    }
  }

  @Test
  void namedTokensExpireAtTheBoundaryAndRejectAmbiguousCredentials() {
    Instant now = Instant.parse("2026-10-01T00:00:00Z");
    var settings = new PoolPilotProperties.Admin();
    var reader = user("reader", READER, PoolPilotProperties.Admin.Role.READER);
    var writer = user("alice", WRITER, PoolPilotProperties.Admin.Role.OPERATOR);
    writer.setExpiresAt(now.plusSeconds(1));
    settings.setUsers(List.of(reader, writer));
    var authenticator = new TokenAdminAuthenticator(settings, Clock.fixed(now, ZoneOffset.UTC));
    assertEquals(
        new AdminIdentity("reader", false),
        authenticator.authenticate(request(READER)).orElseThrow());
    assertEquals(
        new AdminIdentity("alice", true),
        authenticator.authenticate(request(WRITER)).orElseThrow());
    assertTrue(authenticator.authenticate(request("wrong-token-for-testing")).isEmpty());
    assertTrue(authenticator.authenticate(request(null)).isEmpty());
    assertTrue(
        new TokenAdminAuthenticator(settings, Clock.fixed(now.plusSeconds(1), ZoneOffset.UTC))
            .authenticate(request(WRITER))
            .isEmpty());
    settings.setToken(WRITER);
    assertThrows(IllegalArgumentException.class, () -> new TokenAdminAuthenticator(settings));
    settings.setToken("");
    settings.setUsers(
        List.of(reader, user("other", READER, PoolPilotProperties.Admin.Role.OPERATOR)));
    assertThrows(IllegalArgumentException.class, () -> new TokenAdminAuthenticator(settings));
  }

  @Test
  void readerCannotMutateAndWriterAuditUsesAuthenticatedIdentity() throws Exception {
    var settings = new PoolPilotProperties.Admin();
    settings.setUsers(
        List.of(
            user("reader", READER, PoolPilotProperties.Admin.Role.READER),
            user("alice", WRITER, PoolPilotProperties.Admin.Role.OPERATOR)));
    var mapper = new ObjectMapper().findAndRegisterModules();
    var registration =
        new PoolPilotAdminAutoConfiguration()
            .poolPilotAdminAuthentication(new TokenAdminAuthenticator(settings), mapper);
    assertEquals(-90, registration.getOrder());
    try (var registry = new ManagedPoolRegistry(0);
        var monitor = new PoolAlertMonitor(registry, new PoolPilotProperties.Alerts(), List.of());
        var refresher =
            new PoolConfigRefresher(registry, List.of(), "orders-app", "instance-a", 1000)) {
      var before = new PoolSettings(1, 2, 8, 60, false, RejectionPolicy.ABORT, 1000);
      registry.register("orders", before, TaskDecorator.identity());
      var mvc =
          MockMvcBuilders.standaloneSetup(
                  new PoolPilotAdminController(registry, monitor, refresher))
              .addFilters(registration.getFilter())
              .build();
      mvc.perform(get("/poolpilot/api/info").header("X-PoolPilot-Token", READER))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.user").value("reader"))
          .andExpect(jsonPath("$.writable").value(false))
          .andExpect(jsonPath("$.configSources.application").value("orders-app"))
          .andExpect(
              content()
                  .string(
                      org.hamcrest.Matchers.not(
                          org.hamcrest.Matchers.containsString("tokenSha256"))));
      String body =
          mapper.writeValueAsString(
              new PoolPilotAdminController.UpdateRequest(
                  1,
                  "resize orders",
                  new PoolSettings(1, 2, 16, 60, false, RejectionPolicy.ABORT, 1000)));
      mvc.perform(
              put("/poolpilot/api/pools/orders")
                  .header("X-PoolPilot-Token", READER)
                  .contentType("application/json")
                  .content(body))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("FORBIDDEN"));
      mvc.perform(post("/poolpilot/api/demo/load").header("X-PoolPilot-Token", READER))
          .andExpect(status().isForbidden());
      assertEquals(1, registry.get("orders").configurationVersion());
      assertTrue(registry.changes().isEmpty());
      mvc.perform(
              put("/poolpilot/api/pools/orders")
                  .header("X-PoolPilot-Token", WRITER)
                  .header("X-PoolPilot-User", "spoofed-user")
                  .contentType("application/json")
                  .content(body))
          .andExpect(status().isOk());
      assertEquals("alice", registry.changes().get(0).actor());
      mvc.perform(get("/poolpilot/api/config-sources").header("X-PoolPilot-Token", READER))
          .andExpect(status().isOk());
      mvc.perform(get("/poolpilot/api/notifications").header("X-PoolPilot-Token", READER))
          .andExpect(status().isOk());
    }
  }

  @Test
  void applicationAuthenticationRequiresAValidatedPrincipalAndRole() {
    var settings = new PoolPilotProperties();
    settings.getAdmin().setAuthentication(PoolPilotProperties.Admin.Authentication.APPLICATION);
    var authenticator = new PoolPilotAdminAutoConfiguration().poolPilotAdminAuthenticator(settings);
    var request = request(WRITER);
    request.addHeader("X-PoolPilot-User", "alice");
    assertTrue(authenticator.authenticate(request).isEmpty());
    request.setUserPrincipal(() -> "alice");
    assertTrue(authenticator.authenticate(request).isEmpty());
    request.addUserRole("POOLPILOT_READ");
    assertEquals(
        new AdminIdentity("alice", false), authenticator.authenticate(request).orElseThrow());
    request.addUserRole("POOLPILOT_WRITE");
    assertEquals(
        new AdminIdentity("alice", true), authenticator.authenticate(request).orElseThrow());
  }
}
