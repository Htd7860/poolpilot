# Source comparison with dynamic-tp and Hippo4j

[简体中文](comparison.md) · [English](comparison.en.md)

Date: 2026-10-08. PoolPilot: 0.1.0.

PoolPilot provides tuning, version checks, local recovery, rollback, task metrics and Prometheus integration within one JVM. It includes explicit Spring Async integration, HTTP/Nacos refresh, unfinished task timeouts, isolated notification delivery and named identity. The target is Java 17/21 with Boot 3; reference projects retain a broader legacy/third-party adapter ecosystem.

## Source baselines

The repositories were cloned and their POMs, bean processors, executors, queues, refreshers, alerts, metrics and tests inspected. Reference builds and benchmarks were not executed. These commits describe source snapshots, not verified release support matrices.

| Project    | Commit                                                                                          | Date       | Parent version          |
| ---------- | ----------------------------------------------------------------------------------------------- | ---------- | ----------------------- |
| dynamic-tp | [e304f183](https://github.com/dromara/dynamic-tp/tree/e304f1832db7afc2a83bd7440b1b95018c0562c5) | 2026-08-14 | 1.2.2                   |
| Hippo4j    | [c734d00e](https://github.com/opengoofy/hippo4j/tree/c734d00ec1e560fb63570220eee708c7440496fd)  | 2026-03-12 | 2.0.0-SNAPSHOT, develop |

## Compatibility

| Area           | PoolPilot                                                | Reference implementation and impact                                                                                                                                                                                                                                                                                           |
| -------------- | -------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Runtime        | Java 17/21, Boot 3.4.13/3.5.16; see validation matrix    | dynamic-tp targets Java 8 and imports Boot 2.7.18. Hippo4j targets Java 8 in its parent and contains a separate Boot 3 Web adapter configuration. This shows additional integration paths, not universal module compatibility.                                                                                                |
| Existing pools | Registry-created pools and explicit Spring Async adapter | dynamic-tp adapts annotated JUC and Spring executors. Hippo4j handles its DynamicThreadPoolExecutor and supports Spring, TTL and Zipkin wrappers. PoolPilot preserves named Async calls through an explicit replacement bean, without automatically taking over existing instances. Reference adaptation is also conditional. |
| Dependencies   | Base starter without MVC; optional admin starter         | Reference projects separate core, configuration, monitoring and adapters. PoolPilot separates management from background governance; Actuator supports metric auto-configuration.                                                                                                                                             |
| Context        | MDC plus ordered Spring TaskDecorator composition        | dynamic-tp offers multiple wrappers; Hippo4j contains TTL and Zipkin adapters. PoolPilot composes application Spring decorators; SecurityContext/TTL require appropriate decorators. Replacing the core decorator replaces default MDC composition.                                                                           |

Sources: [dynamic-tp POM](https://github.com/dromara/dynamic-tp/blob/e304f1832db7afc2a83bd7440b1b95018c0562c5/pom.xml), [DtpPostProcessor](https://github.com/dromara/dynamic-tp/blob/e304f1832db7afc2a83bd7440b1b95018c0562c5/spring/src/main/java/org/dromara/dynamictp/spring/DtpPostProcessor.java), [Hippo4j processor](https://github.com/opengoofy/hippo4j/blob/c734d00ec1e560fb63570220eee708c7440496fd/starters/threadpool/config/src/main/java/cn/hippo4j/config/springboot/starter/support/DynamicThreadPoolPostProcessor.java), [Boot 3 Web adapter](https://github.com/opengoofy/hippo4j/blob/c734d00ec1e560fb63570220eee708c7440496fd/starters/threadpool/adapter/web/src/main/java/cn/hippo4j/springboot3/starter/adapter/web/WebAdapterConfiguration.java), [PoolPilot starter](../poolpilot-spring-boot-starter/pom.xml).

## Availability

PoolPilot retains completed slow-task counts and separately scans bounded unfinished queue/run tracking. Events include task ID, threshold, elapsed time and worker name; each phase reports at most once. Tracking overflow is counted and cleanup covers completion/rejection/cancellation/removal/shutdown. Observation does not interrupt work or capture stacks. Scan precision depends on scheduling and can miss brief violations completed before a scan.

dynamic-tp schedules queue timeouts on submission and run timeouts before execution, cancelling timers at the corresponding transitions. Its run-time timer can alert before completion, capture the worker stack and optionally attempt interruption. Hippo4j's inspected AbstractTaskTimerPlugin checks elapsed time in afterExecute; its timeout notification plugin is a completion-time mechanism. Interruption cannot terminate code that ignores it.

Sources: [TaskTimeoutAware](https://github.com/dromara/dynamic-tp/blob/e304f1832db7afc2a83bd7440b1b95018c0562c5/core/src/main/java/org/dromara/dynamictp/core/aware/TaskTimeoutAware.java), [RunTimeoutTimerTask](https://github.com/dromara/dynamic-tp/blob/e304f1832db7afc2a83bd7440b1b95018c0562c5/core/src/main/java/org/dromara/dynamictp/core/timer/RunTimeoutTimerTask.java), [Hippo4j timer plugin](https://github.com/opengoofy/hippo4j/blob/c734d00ec1e560fb63570220eee708c7440496fd/threadpool/core/src/main/java/cn/hippo4j/core/executor/plugin/impl/AbstractTaskTimerPlugin.java).

PoolPilot separates sampling from a bounded delivery executor. Saturation drops new delivery jobs and records them; events remain in history. Failure isolation and a shutdown deadline are verified with blocked-listener/saturation tests. TaskObserver callbacks occupy completion threads. dynamic-tp's AlarmManager uses a separate executor with a 2000-entry queue and DiscardOldest: it isolates slow notifications but can discard old work. PoolPilot exposes accepted/completed/failed/dropped/queued/active statistics through API and Micrometer. Neither strategy guarantees delivery.

Sources: [PoolAlertMonitor](../poolpilot-spring-boot-starter/src/main/java/dev/poolpilot/spring/PoolAlertMonitor.java), [AlarmManager](https://github.com/dromara/dynamic-tp/blob/e304f1832db7afc2a83bd7440b1b95018c0562c5/core/src/main/java/org/dromara/dynamictp/core/notifier/manager/AlarmManager.java).

PoolPilot retains settings, versions and 200 changes through PoolStateStore. Recovery, failed saves, version conflicts and rollback are tested. Exclusive path ownership is local storage coordination, not multi-instance broadcasting. Serialization, file I/O and force(true) occur under registry and pool configuration locks. Submission continues, while snapshots, other changes and shutdown may wait. Asynchronous writes would alter the current failed-save contract and require a defined recovery model.

dynamic-tp's Nacos refresher listens for configuration events. Hippo4j's refresher parses and binds configuration before publishing refresh events, and its server contains client update dispatch. PoolPilot adds PoolConfigSource SPI, bounded HTTP/Nacos polling and application/instance identity. Publisher-owned revisions deduplicate updates; local expectedVersion prevents overwriting manual changes. Accepted revisions persist with settings. Disconnects preserve state and reconnects ignore old revisions. Updates apply independently, without distributed transactions or a central cluster console.

Sources: [PoolPilot registry](../poolpilot-core/src/main/java/dev/poolpilot/core/ManagedPoolRegistry.java), [file store](../poolpilot-spring-boot-starter/src/main/java/dev/poolpilot/spring/FilePoolStateStore.java), [Nacos refresher](https://github.com/dromara/dynamic-tp/blob/e304f1832db7afc2a83bd7440b1b95018c0562c5/starter/starter-configcenter/starter-nacos/src/main/java/org/dromara/dynamictp/starter/nacos/refresher/NacosRefresher.java), [Hippo4j refresher](https://github.com/opengoofy/hippo4j/blob/c734d00ec1e560fb63570220eee708c7440496fd/starters/threadpool/config/src/main/java/cn/hippo4j/config/springboot/starter/refresher/AbstractConfigThreadPoolDynamicRefresh.java).

A shared token retains token-admin semantics. Named SHA-256 token digests support reader/operator roles, expiry and authenticated audit names. APPLICATION mode uses an upstream-verified Principal/roles; AdminAuthenticator supports other verified identity mappings without trusting caller user headers. Hippo4j's JWT filter integrates Spring Security authentication and a user context, offering a broader identity integration path. The filter alone does not prove every endpoint's authorization. Permissions cover all registered pools, not per-pool ACLs or account issuance. Actuator, TLS and cookie-authenticated CSRF remain application responsibilities.

Sources: [PoolPilot authentication](../poolpilot-spring-boot-admin-starter/src/main/java/dev/poolpilot/spring/PoolPilotAdminAutoConfiguration.java), [JWT filter](https://github.com/opengoofy/hippo4j/blob/c734d00ec1e560fb63570220eee708c7440496fd/threadpool/server/auth/src/main/java/cn/hippo4j/auth/filter/JWTAuthorizationFilter.java).

## Performance

Metric counters previously read complete snapshots, repeatedly acquiring configuration locks, inspecting worker state and allocating snapshot objects. New samples could also trigger percentile sorting. PoolMetricsBinder now reads individual LongAdders through taskCount(TaskCounter), and reads capacity and configuration version directly. Names and counting semantics remain unchanged. A regression test verifies these reads during a blocked state save. Separate reads are not an atomic multi-field snapshot; worker and queue gauges retain their own locks.

[JMH benchmarks](../benchmarks/README.md) compare seven snapshot-based counter reads with seven direct reads, and compare JDK and managed submit/get using the same queue. Conditions, errors and raw results are documented there. Additional trials cover four producers/four consumers, half-cancelled queue filtering and sampled short-task tails. These measure neither reference frameworks nor complete scrapes/governance requests/application throughput.

PoolPilot uses an ArrayDeque with one lock for enqueue, dequeue and resize. Reference linked queues use separate put/take locks and an atomic count. Split locks may permit producer/consumer overlap, while linked nodes allocate individually; lock counts alone cannot predict throughput. Queue contention requires multiple producers, consumers, queue sizes and task durations.

Task wrapping allocates TimedTask and TaskSample, and sample writes synchronize on a 256-entry ring. Short-task workloads need allocation, lock and Timer-cost measurements. dynamic-tp uses a 4096-entry uniform reservoir with interval reset; Hippo4j's Micrometer handler reads cached state. Their percentile populations and freshness differ from PoolPilot's recent-completion window.

PoolPilot purge() now filters in a single locked linear pass, preserving remaining order and producer wakeups, then records cancellation outside the queue lock. Behavioral tests and standalone filtering benchmarks cover this change. The allowed million-entry capacity is a validation ceiling, not a memory recommendation.

Sources: [PoolMetricsBinder](../poolpilot-spring-boot-starter/src/main/java/dev/poolpilot/spring/PoolMetricsBinder.java), [PoolPilot queue](../poolpilot-core/src/main/java/dev/poolpilot/core/ResizableCapacityBlockingQueue.java), [dynamic-tp queue](https://github.com/dromara/dynamic-tp/blob/e304f1832db7afc2a83bd7440b1b95018c0562c5/common/src/main/java/org/dromara/dynamictp/common/queue/VariableLinkedBlockingQueue.java), [reservoir](https://github.com/dromara/dynamic-tp/blob/e304f1832db7afc2a83bd7440b1b95018c0562c5/core/src/main/java/org/dromara/dynamictp/core/metric/LimitedUniformReservoir.java), [Hippo4j monitor](https://github.com/opengoofy/hippo4j/blob/c734d00ec1e560fb63570220eee708c7440496fd/threadpool/monitor/micrometer/src/main/java/cn/hippo4j/monitor/micrometer/DynamicThreadPoolMicrometerMonitorHandler.java).

## Implementation and verification

| Item | Work                                                | Verified behavior                                                                                                                                                  |
| ---- | --------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 1    | Isolate notifications                               | Blocked listeners do not delay sampling; bounded queue, observable drops/failures and bounded shutdown                                                             |
| 2    | Queue and in-flight run timeouts                    | Unfinished blocked work can alert; tracking is cleaned on completion/cancellation/rejection; no duplicate notification; observation without automatic interruption |
| 3    | Optional management and Spring executor integration | Non-web applications do not inherit MVC; explicit Async integration preserves lifecycle and decorators                                                             |
| 4    | Contention, cancellation and short-task benchmarks  | Reproducible producer loads, GC and tail latency; preserve resize and cancellation behavior                                                                        |
| 5    | Configuration sources and user auditing             | Reconnect/stale updates do not overwrite current configuration; real actors and defined multi-instance version sources                                             |

Validation: [AlertDeliveryTest](../poolpilot-spring-boot-starter/src/test/java/dev/poolpilot/spring/AlertDeliveryTest.java), [TaskTimeoutTest](../poolpilot-core/src/test/java/dev/poolpilot/core/TaskTimeoutTest.java), [AsyncIntegrationTest](../poolpilot-spring-boot-starter/src/test/java/dev/poolpilot/spring/AsyncIntegrationTest.java), [ConfigSourceTest](../poolpilot-spring-boot-starter/src/test/java/dev/poolpilot/spring/ConfigSourceTest.java), [AdminAuthenticationTest](../poolpilot-spring-boot-admin-starter/src/test/java/dev/poolpilot/spring/AdminAuthenticationTest.java), [benchmarks](../benchmarks/README.md).

Java 8 / Boot 2 require separate compilation and dependency plans; records, switch expressions and Jakarta APIs cannot be made compatible by editing one POM property. Verify support with a test matrix. Existing single-instance governance is usable; applications needing a cluster console and broad adapters should evaluate the reference projects' modules.
