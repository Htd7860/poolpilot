package dev.poolpilot.spring;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import dev.poolpilot.core.*;
import dev.poolpilot.spring.ConsoleMessages.ApiError;
import dev.poolpilot.spring.ConsoleMessages.InvalidRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/poolpilot/api")
public final class PoolPilotAdminController {
  public record SettingsRequest(
      @JsonDeserialize(using = StrictNumbers.IntegerValue.class) Integer coreSize,
      @JsonDeserialize(using = StrictNumbers.IntegerValue.class) Integer maxSize,
      @JsonDeserialize(using = StrictNumbers.IntegerValue.class) Integer queueCapacity,
      @JsonDeserialize(using = StrictNumbers.LongValue.class) Long keepAliveSeconds,
      Boolean allowCoreTimeout,
      RejectionPolicy rejectionPolicy,
      @JsonDeserialize(using = StrictNumbers.LongValue.class) Long slowTaskMillis) {
    static SettingsRequest from(PoolSettings settings) {
      return new SettingsRequest(
          settings.coreSize(),
          settings.maxSize(),
          settings.queueCapacity(),
          settings.keepAliveSeconds(),
          settings.allowCoreTimeout(),
          settings.rejectionPolicy(),
          settings.slowTaskMillis());
    }

    PoolSettings validated() {
      if (coreSize == null
          || maxSize == null
          || queueCapacity == null
          || keepAliveSeconds == null
          || allowCoreTimeout == null
          || rejectionPolicy == null
          || slowTaskMillis == null) throw new InvalidRequest("SETTINGS_REQUIRED");
      if (coreSize < 0 || maxSize < 1 || coreSize > maxSize || maxSize > 1024)
        throw new InvalidRequest("THREAD_RANGE");
      if (queueCapacity < 1 || queueCapacity > 1_000_000) throw new InvalidRequest("QUEUE_RANGE");
      if (keepAliveSeconds < 0
          || keepAliveSeconds > 86400
          || (allowCoreTimeout && keepAliveSeconds == 0))
        throw new InvalidRequest("KEEP_ALIVE_RANGE");
      if (slowTaskMillis < 1 || slowTaskMillis > 86400000)
        throw new InvalidRequest("SLOW_TASK_RANGE");
      return new PoolSettings(
          coreSize,
          maxSize,
          queueCapacity,
          keepAliveSeconds,
          allowCoreTimeout,
          rejectionPolicy,
          slowTaskMillis);
    }
  }

  public record UpdateRequest(
      @JsonDeserialize(using = StrictNumbers.LongValue.class) Long expectedVersion,
      String reason,
      SettingsRequest settings) {
    public UpdateRequest(long expectedVersion, String reason, PoolSettings settings) {
      this(
          Long.valueOf(expectedVersion),
          reason,
          settings == null ? null : SettingsRequest.from(settings));
    }
  }

  public record RollbackRequest(
      @JsonDeserialize(using = StrictNumbers.LongValue.class) Long changeId,
      @JsonDeserialize(using = StrictNumbers.LongValue.class) Long expectedVersion,
      String reason) {}

  @PostMapping("/pools/{name}/rollback")
  public PoolSnapshot rollback(
      @PathVariable String name,
      @RequestBody RollbackRequest request,
      @RequestAttribute(AdminIdentity.ATTRIBUTE) AdminIdentity identity) {
    if (request.changeId() == null || request.changeId() < 1)
      throw new InvalidRequest("CHANGE_REQUIRED");
    if (request.expectedVersion() == null || request.expectedVersion() < 1)
      throw new InvalidRequest("VERSION_REQUIRED");
    if (request.reason() == null || request.reason().isBlank() || request.reason().length() > 200)
      throw new InvalidRequest("REASON_REQUIRED");
    return registry.rollback(
        name, request.changeId(), request.expectedVersion(), request.reason(), identity.name());
  }

  @ExceptionHandler(ChangeNotFoundException.class)
  public ResponseEntity<ApiError> changeMissing(ChangeNotFoundException ex, Locale locale) {
    return error(404, "CHANGE_NOT_FOUND", locale);
  }

  @ExceptionHandler(StateStoreException.class)
  public ResponseEntity<ApiError> storage(StateStoreException ex, Locale locale) {
    org.slf4j.LoggerFactory.getLogger(getClass()).error("PoolPilot change persistence failed", ex);
    return error(503, "PERSISTENCE_FAILED", locale);
  }

  private final ManagedPoolRegistry registry;
  private final PoolAlertMonitor monitor;
  private final PoolConfigRefresher refresher;

  public PoolPilotAdminController(
      ManagedPoolRegistry registry, PoolAlertMonitor monitor, PoolConfigRefresher refresher) {
    this.registry = registry;
    this.monitor = monitor;
    this.refresher = refresher;
  }

  @GetMapping("/pools")
  public List<PoolSnapshot> pools() {
    return registry.snapshots();
  }

  public record ConsoleInfo(
      boolean configurationPersistence,
      int changeHistoryLimit,
      String user,
      boolean writable,
      AlertDeliveryStats notifications,
      PoolConfigRefresher.Status configSources) {}

  @GetMapping("/info")
  public ConsoleInfo info(@RequestAttribute(AdminIdentity.ATTRIBUTE) AdminIdentity identity) {
    return new ConsoleInfo(
        registry.persistenceEnabled(),
        200,
        identity.name(),
        identity.writable(),
        monitor.deliveryStats(),
        refresher.status());
  }

  @GetMapping("/config-sources")
  public PoolConfigRefresher.Status configSources() {
    return refresher.status();
  }

  @GetMapping("/pools/{name}")
  public PoolSnapshot pool(@PathVariable String name) {
    return registry.get(name).snapshot();
  }

  @GetMapping("/pools/{name}/sources")
  public Map<String, Long> sources(@PathVariable String name) {
    return registry.sourceRevisions(name);
  }

  @GetMapping("/notifications")
  public AlertDeliveryStats notifications() {
    return monitor.deliveryStats();
  }

  @PutMapping("/pools/{name}")
  public PoolSnapshot update(
      @PathVariable String name,
      @RequestBody UpdateRequest request,
      @RequestAttribute(AdminIdentity.ATTRIBUTE) AdminIdentity identity) {
    if (request.settings() == null) throw new InvalidRequest("SETTINGS_REQUIRED");
    if (request.expectedVersion() == null || request.expectedVersion() < 1)
      throw new InvalidRequest("VERSION_REQUIRED");
    if (request.reason() == null || request.reason().isBlank() || request.reason().length() > 200)
      throw new InvalidRequest("REASON_REQUIRED");
    return registry.reconfigure(
        name,
        request.settings().validated(),
        request.expectedVersion(),
        request.reason(),
        identity.name());
  }

  @GetMapping("/alerts")
  public List<PoolAlert> alerts(Locale locale) {
    return monitor.alerts().stream()
        .map(
            a ->
                new PoolAlert(
                    a.id(),
                    a.timestamp(),
                    a.pool(),
                    a.kind(),
                    ConsoleMessages.alert(a.kind(), a.value(), locale),
                    a.value(),
                    a.configVersion(),
                    a.task()))
        .toList();
  }

  @GetMapping("/changes")
  public List<PoolChange> changes() {
    return registry.changes();
  }

  @ExceptionHandler(VersionConflictException.class)
  public ResponseEntity<ApiError> conflict(VersionConflictException ex, Locale locale) {
    return ResponseEntity.status(409)
        .body(
            ConsoleMessages.error(
                "VERSION_CONFLICT", locale, Map.of("version", ex.actualVersion())));
  }

  @ExceptionHandler(NoSuchElementException.class)
  public ResponseEntity<ApiError> missing(NoSuchElementException ex, Locale locale) {
    return error(404, "POOL_NOT_FOUND", locale);
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ApiError> state(IllegalStateException ex, Locale locale) {
    return error(409, "POOL_CLOSED", locale);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiError> invalid(IllegalArgumentException ex, Locale locale) {
    return error(400, ex instanceof InvalidRequest r ? r.code() : "INVALID_REQUEST", locale);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiError> malformed(HttpMessageNotReadableException ex, Locale locale) {
    return error(400, "INVALID_REQUEST", locale);
  }

  private ResponseEntity<ApiError> error(int status, String code, Locale locale) {
    return ResponseEntity.status(status).body(ConsoleMessages.error(code, locale));
  }
}
