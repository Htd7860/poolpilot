package dev.poolpilot.demo;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import dev.poolpilot.core.*;
import dev.poolpilot.spring.ConsoleMessages;
import dev.poolpilot.spring.StrictNumbers;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(prefix = "poolpilot.admin", name = "enabled", havingValue = "true")
@RequestMapping("/poolpilot/api/demo")
public class DemoLoadController {
  public record LoadRequest(
      String pool,
      @JsonDeserialize(using = StrictNumbers.PrimitiveIntegerValue.class) int tasks,
      @JsonDeserialize(using = StrictNumbers.PrimitiveIntegerValue.class) int durationMillis,
      @JsonDeserialize(using = StrictNumbers.PrimitiveIntegerValue.class) int failEvery) {}

  public record LoadResult(int submitted, int accepted, int rejected, String traceId) {}

  private final ManagedPoolRegistry registry;
  private final AtomicBoolean launching = new AtomicBoolean();

  public DemoLoadController(ManagedPoolRegistry registry) {
    this.registry = registry;
  }

  @PostMapping("/load")
  public ResponseEntity<?> load(@RequestBody LoadRequest request, Locale locale) {
    if (request.pool() == null || request.pool().isBlank())
      return error(400, "POOL_REQUIRED", locale);
    if (request.tasks() < 1
        || request.tasks() > 200
        || request.durationMillis() < 1
        || request.durationMillis() > 2000
        || request.failEvery() < 0
        || request.failEvery() > 200) return error(400, "LOAD_RANGE", locale);
    final ManagedThreadPool pool;
    try {
      pool = registry.get(request.pool());
    } catch (NoSuchElementException ex) {
      return error(404, "POOL_NOT_FOUND", locale);
    }
    // submitOrReject prevents policy changes from running demo work on the HTTP thread.
    if (pool.snapshot().settings().rejectionPolicy() != RejectionPolicy.ABORT)
      return error(409, "LOAD_POLICY", locale);
    if (!launching.compareAndSet(false, true)) return error(409, "LOAD_BUSY", locale);
    int accepted = 0, rejected = 0;
    String trace = UUID.randomUUID().toString();
    Map<String, String> previous = MDC.getCopyOfContextMap();
    MDC.put("traceId", trace);
    try {
      for (int i = 1; i <= request.tasks(); i++) {
        final int task = i;
        try {
          pool.submitOrReject(
              () -> {
                try {
                  Thread.sleep(request.durationMillis());
                } catch (InterruptedException ex) {
                  Thread.currentThread().interrupt();
                  throw new CancellationException("demo interrupted");
                }
                if (request.failEvery() > 0 && task % request.failEvery() == 0)
                  throw new IllegalStateException("simulated downstream failure");
                return MDC.get("traceId");
              });
          accepted++;
        } catch (RejectedExecutionException ex) {
          rejected++;
        }
      }
    } finally {
      if (previous == null) MDC.clear();
      else MDC.setContextMap(previous);
      launching.set(false);
    }
    return ResponseEntity.accepted()
        .body(new LoadResult(request.tasks(), accepted, rejected, trace));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<?> malformed(HttpMessageNotReadableException ex, Locale locale) {
    return error(400, "INVALID_REQUEST", locale);
  }

  private ResponseEntity<?> error(int status, String code, Locale locale) {
    return ResponseEntity.status(status).body(ConsoleMessages.error(code, locale));
  }
}
