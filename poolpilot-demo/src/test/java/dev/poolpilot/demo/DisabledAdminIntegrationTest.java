package dev.poolpilot.demo;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"poolpilot.admin.enabled=false", "poolpilot.persistence.enabled=false"})
class DisabledAdminIntegrationTest {
  @Autowired TestRestTemplate http;
  @Autowired ApplicationContext context;

  @Test
  void disablingManagementAlsoRemovesTheDemoLoadEndpoint() {
    assertTrue(context.getBeansOfType(DemoLoadController.class).isEmpty());
    assertEquals(
        404,
        http.postForEntity("/poolpilot/api/demo/load", "{}", String.class).getStatusCode().value());
    assertEquals(
        404, http.getForEntity("/poolpilot/api/pools", String.class).getStatusCode().value());
    assertEquals(
        404, http.getForEntity("/poolpilot/api/auth", String.class).getStatusCode().value());
  }
}
