package dev.poolpilot.spring;

import io.micrometer.core.instrument.*;
import java.util.*;

public final class PoolAlertMetricsBinder implements AutoCloseable {
  private final MeterRegistry registry;
  private final List<Meter> meters = new ArrayList<>();

  public PoolAlertMetricsBinder(PoolAlertMonitor monitor, MeterRegistry registry) {
    this.registry = registry;
    meters.add(
        FunctionCounter.builder(
                "poolpilot.alerts.accepted", monitor, PoolAlertMonitor::deliveryAccepted)
            .register(registry));
    meters.add(
        FunctionCounter.builder(
                "poolpilot.alerts.completed", monitor, PoolAlertMonitor::deliveryCompleted)
            .register(registry));
    meters.add(
        FunctionCounter.builder(
                "poolpilot.alerts.failed", monitor, PoolAlertMonitor::deliveryFailed)
            .register(registry));
    meters.add(
        FunctionCounter.builder(
                "poolpilot.alerts.dropped", monitor, PoolAlertMonitor::deliveryDropped)
            .register(registry));
    meters.add(
        Gauge.builder("poolpilot.alerts.queued", monitor, PoolAlertMonitor::deliveryQueued)
            .register(registry));
    meters.add(
        Gauge.builder("poolpilot.alerts.active", monitor, PoolAlertMonitor::deliveryActive)
            .register(registry));
  }

  @Override
  public void close() {
    meters.forEach(registry::remove);
    meters.clear();
  }
}
