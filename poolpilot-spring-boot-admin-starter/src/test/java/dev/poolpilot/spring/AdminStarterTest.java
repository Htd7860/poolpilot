package dev.poolpilot.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class AdminStarterTest {
  @Test
  void unsafeAdminConfigurationFailsStartup() {
    var runner =
        new WebApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    PoolPilotAutoConfiguration.class, PoolPilotAdminAutoConfiguration.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withPropertyValues("poolpilot.admin.enabled=true");
    for (String token : List.of("short", "contains spaces here", "中文令牌需要明确限制字符范围")) {
      runner
          .withPropertyValues("poolpilot.admin.token=" + token)
          .run(
              c -> {
                assertThat(c).hasFailed();
                assertThat(c.getStartupFailure())
                    .hasRootCauseMessage(
                        "Admin token must contain 12 to 256 visible ASCII characters");
              });
    }
    runner
        .withPropertyValues("poolpilot.admin.token=valid-admin-token")
        .run(
            c -> {
              assertThat(c).hasNotFailed();
              assertThat(c).hasSingleBean(PoolPilotAdminController.class);
            });
  }
}
