# Changelog

## 0.1.0 — 2026-10-08

- Resizable FIFO queue with blocking operations and task preservation on shrink.
- Runtime configuration with version checks, change reasons and bounded audit history.
- Optional file persistence with exclusive locking, atomic replacement and startup validation.
- Rollback to a retained change's previous settings, creating a new version and source reference.
- Direct FutureTask failure observation, queued-task removal and cancellation on forced shutdown.
- MDC propagation with restoration of the worker or caller's previous context.
- Micrometer gauges, task counters and Prometheus wait/execution histograms, including runtime pool registration.
- Direct counter reads that avoid full pool snapshots during metric collection.
- Activity, queue, rejection and slow-task alerts with per-pool cooldowns.
- Token-protected management API and Chinese/English console with filtering, pagination and configuration diffs.
- Bounded demo workloads, JMH benchmarks, Java 17/21 CI and browser acceptance scripts.
- Optional admin starter, named Spring Async executors and ordered Spring task decorators.
- CompletableFuture submission with business failure observation and queued cancellation removal.
- Bounded unfinished queue/run timeout tracking without automatic task interruption.
- Independent bounded notification delivery, observable failures/drops and a shutdown deadline.
- HTTP/Nacos configuration polling with application scope, persistent source revisions and local version checks.
- Named token reader/operator roles, expiry and individual audit identity; application authentication SPI.
- Linear bulk cancellation cleanup and benchmarks for contention, cancellation and short-task tails.

- 管理台按后台认证模式显示入口；管理密钥由部署者提供，不设置默认令牌。
- The console selects the configured authentication entry; deployment credentials have no default.
