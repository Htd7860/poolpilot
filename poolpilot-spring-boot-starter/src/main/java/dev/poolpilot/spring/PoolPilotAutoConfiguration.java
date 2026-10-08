package dev.poolpilot.spring;

import dev.poolpilot.core.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(
    after = {MetricsAutoConfiguration.class, CompositeMeterRegistryAutoConfiguration.class})
@ConditionalOnProperty(
    prefix = "poolpilot",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@EnableConfigurationProperties(PoolPilotProperties.class)
public class PoolPilotAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean(TaskDecorator.class)
  public TaskDecorator poolPilotTaskDecorator(
      ObjectProvider<org.springframework.core.task.TaskDecorator> decorators) {
    java.util.List<TaskDecorator> chain = new java.util.ArrayList<>();
    chain.add(new MdcTaskDecorator());
    decorators.orderedStream().forEach(decorator -> chain.add(decorator::decorate));
    return TaskDecorator.compose(chain);
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  public ManagedPoolRegistry managedPoolRegistry(
      PoolPilotProperties properties, TaskDecorator decorator) {
    var persistence = properties.getPersistence();
    if (properties.getShutdownGraceSeconds() < 0 || properties.getShutdownGraceSeconds() > 300)
      throw new IllegalArgumentException("Shutdown grace must be between 0 and 300 seconds");
    if (persistence.isEnabled()
        && (persistence.getPath() == null || persistence.getPath().isBlank()))
      throw new IllegalArgumentException("PoolPilot state path is required");
    ManagedPoolRegistry registry =
        new ManagedPoolRegistry(
            properties.getShutdownGraceSeconds(),
            persistence.isEnabled()
                ? new FilePoolStateStore(Path.of(persistence.getPath()))
                : PoolStateStore.disabled());
    try {
      for (PoolPilotProperties.Pool p : properties.getPools())
        registry.register(p.getName(), p.settings(), decorator);
      return registry;
    } catch (RuntimeException ex) {
      registry.close();
      throw ex;
    }
  }

  @Bean
  @ConditionalOnMissingBean(PoolAlertListener.class)
  public PoolAlertListener loggingPoolAlertListener() {
    return alert ->
        LoggerFactory.getLogger("dev.poolpilot.alert")
            .warn("{} {} {}", alert.pool(), alert.kind(), alert.message());
  }

  @Bean(initMethod = "start", destroyMethod = "close")
  @ConditionalOnMissingBean
  public PoolAlertMonitor poolAlertMonitor(
      ManagedPoolRegistry registry,
      PoolPilotProperties properties,
      ObjectProvider<PoolAlertListener> listeners) {
    return new PoolAlertMonitor(
        registry, properties.getAlerts(), listeners.orderedStream().toList());
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnBean(MeterRegistry.class)
  @ConditionalOnMissingBean(PoolAlertMetricsBinder.class)
  public PoolAlertMetricsBinder poolAlertMetricsBinder(
      PoolAlertMonitor monitor, MeterRegistry registry) {
    return new PoolAlertMetricsBinder(monitor, registry);
  }

  @Bean(initMethod = "start", destroyMethod = "close")
  @ConditionalOnMissingBean(TaskTimeoutMonitor.class)
  public TaskTimeoutMonitor taskTimeoutMonitor(
      ManagedPoolRegistry registry, PoolAlertMonitor monitor, PoolPilotProperties properties) {
    return new TaskTimeoutMonitor(registry, monitor, properties.getTimeouts());
  }

  @Bean(initMethod = "start", destroyMethod = "close")
  @ConditionalOnMissingBean(PoolConfigRefresher.class)
  public PoolConfigRefresher poolConfigRefresher(
      ManagedPoolRegistry registry,
      PoolPilotProperties properties,
      ObjectProvider<PoolConfigSource> sources) {
    var settings = properties.getConfigSources();
    var all = new java.util.ArrayList<>(sources.orderedStream().toList());
    for (var source : settings.getHttp())
      all.add(
          new HttpPoolConfigSource(
              source.getId(),
              java.net.URI.create(source.getUrl()),
              source.getBearerToken(),
              java.time.Duration.ofMillis(source.getTimeoutMillis()),
              source.getFormat(),
              source.getAccessToken()));
    return new PoolConfigRefresher(
        registry,
        all,
        settings.getApplication(),
        settings.getInstance(),
        settings.getIntervalMillis());
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnBean(MeterRegistry.class)
  @ConditionalOnMissingBean(PoolMetricsBinder.class)
  public PoolMetricsBinder poolMetricsBinder(
      ManagedPoolRegistry registry, MeterRegistry meterRegistry) {
    return new PoolMetricsBinder(registry, meterRegistry);
  }
}
