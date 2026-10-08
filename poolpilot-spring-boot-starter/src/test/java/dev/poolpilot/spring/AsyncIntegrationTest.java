package dev.poolpilot.spring;

import static org.junit.jupiter.api.Assertions.*;

import dev.poolpilot.core.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.annotation.*;
import org.springframework.util.ClassUtils;

@Timeout(10)
class AsyncIntegrationTest {
  private static final ThreadLocal<String> TENANT = new ThreadLocal<>();

  @Configuration(proxyBeanMethods = false)
  @EnableAsync
  static class Application {
    @Bean
    AsyncTaskExecutor ordersExecutor(ManagedPoolRegistry registry) {
      return new PoolPilotTaskExecutor(registry, "orders");
    }

    @Bean
    AsyncService asyncService() {
      return new AsyncService();
    }

    @Bean
    org.springframework.core.task.TaskDecorator tenantDecorator() {
      return task -> {
        String tenant = TENANT.get();
        return () -> {
          String previous = TENANT.get();
          try {
            if (tenant == null) TENANT.remove();
            else TENANT.set(tenant);
            task.run();
          } finally {
            if (previous == null) TENANT.remove();
            else TENANT.set(previous);
          }
        };
      };
    }
  }

  static class AsyncService {
    @Async("ordersExecutor")
    public CompletableFuture<String> read() {
      return CompletableFuture.completedFuture(
          MDC.get("trace") + ":" + TENANT.get() + ":" + Thread.currentThread().getName());
    }

    @Async("ordersExecutor")
    public CompletableFuture<String> fail() {
      throw new IllegalStateException("business failed");
    }
  }

  @AfterEach
  void clear() {
    MDC.clear();
    TENANT.remove();
  }

  @Test
  void namedAsyncExecutorPreservesDecoratorsFailuresAndRegistryLifecycleWithoutMvc() {
    assertFalse(
        ClassUtils.isPresent(
            "org.springframework.web.servlet.DispatcherServlet", getClass().getClassLoader()));
    ManagedThreadPool[] managed = new ManagedThreadPool[1];
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PoolPilotAutoConfiguration.class))
        .withUserConfiguration(Application.class)
        .withPropertyValues(
            "poolpilot.pools[0].name=orders",
            "poolpilot.pools[0].core-size=1",
            "poolpilot.pools[0].max-size=1",
            "poolpilot.timeouts.enabled=false")
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              var service = context.getBean(AsyncService.class);
              managed[0] = context.getBean(ManagedPoolRegistry.class).get("orders");
              MDC.put("trace", "request");
              TENANT.set("customer");
              assertEquals(
                  "request:customer:poolpilot-orders-1", service.read().get(1, TimeUnit.SECONDS));
              MDC.clear();
              TENANT.remove();
              assertEquals("null:null:poolpilot-orders-1", service.read().get(1, TimeUnit.SECONDS));
              var failure =
                  assertThrows(
                      ExecutionException.class, () -> service.fail().get(1, TimeUnit.SECONDS));
              assertEquals("business failed", failure.getCause().getMessage());
            });
    assertTrue(managed[0].isTerminated());
    assertEquals(1, managed[0].taskCount(TaskCounter.FAILED));
  }
}
