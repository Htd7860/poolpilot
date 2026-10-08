# Governance configuration

[简体中文](governance.md) · [English](governance.en.md)

## Spring Async and CompletableFuture

Declare `order-io` in YAML and replace the construction of your named executor bean. Existing methods can retain `@Async("orderExecutor")`; the registry owns shutdown.

```java
import dev.poolpilot.core.ManagedPoolRegistry;
import dev.poolpilot.spring.PoolPilotTaskExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;

@Configuration
@EnableAsync
public class AsyncConfiguration {
    @Bean
    public AsyncTaskExecutor orderExecutor(ManagedPoolRegistry registry) {
        return new PoolPilotTaskExecutor(registry, "order-io");
    }
}
```

`submitStage(Callable<T>)` and the adapter's `submitCompletable` link CompletableFuture to the original FutureTask, retaining business failure/cancellation statistics. Cancelling queued work removes it and reclaims capacity. Running cancellation attempts interruption; work that ignores interruption remains tracked until it returns. Native `CompletableFuture.supplyAsync(..., pool)` still captures business failures internally, so use result callbacks or `submitStage` for reliable outcome statistics.

The base starter composes ordered Spring TaskDecorator beans inside an outer MDC wrapper. A custom core TaskDecorator bean replaces this default composition. Decorators must synchronously run their task once; database transactions do not propagate across threads.

## Queue and run timeouts

```yaml
poolpilot:
  timeouts:
    enabled: true
    interval-millis: 250
    queue-millis: 10000
    run-millis: 30000
    max-tracked-tasks: 10000
```

Enabled by default in the starter, disabled in plain core unless setTimeoutPolicy is called. A zero threshold disables that phase. Thresholds are captured at submission; each phase reports at most one event. Cooldown can suppress delivery without suppressing timeout counters. Detection depends on scan scheduling; work completing before a scan may not report a brief threshold violation.

Events contain pool, local task ID, phase, elapsed/threshold milliseconds, configuration version at detection and running thread name. They do not collect arguments/stacks or interrupt/cancel tasks. IDs are scoped to the current pool lifetime. Tracking overflow preserves execution and increments `poolpilot.tasks.tracking.dropped`.

Completion, rejection, remove/purge, forced shutdown and monitor shutdown clean tracking. After queued Future cancellation, purge also reclaims queue capacity. Direct queue mutation bypasses task accounting/lifecycle. `taskTimeouts()` consumes unreported events and is owned by the starter monitor when installed.

Meters: `poolpilot.tasks.queue.timeouts`, `poolpilot.tasks.run.timeouts`, `poolpilot.tasks.tracking.dropped`, `poolpilot.tasks.tracked`, tagged by pool.

## Notification isolation

```yaml
poolpilot:
  alerts:
    notification-threads: 1
    notification-queue-capacity: 128
    notification-shutdown-millis: 1000
```

Events enter the 200-entry in-memory history before asynchronous delivery. Saturation drops a new delivery job and increments dropped; the event remains queryable. Listener failures increment failed without skipping later listeners. Listeners in one job run in order, so a blocked listener delays other channels in that job while sampling remains independent.

`/poolpilot/api/notifications` returns accepted/completed/failed/dropped/queued/active/closed. Failed counts listener errors; completed counts finished jobs, so both can increase. Corresponding Micrometer meters use the `poolpilot.alerts` prefix.

After its bounded shutdown wait, the executor interrupts active jobs and discards queued jobs. Interrupt-ignoring daemon listeners may continue; use bounded network I/O in custom channels. There is no durable delivery, retry or delivery guarantee.

## External configuration

An independent scheduler polls every 5000 ms by default. At most eight sources and 32 updates per document are accepted. HTTP responses are capped at 1 MiB, request timeouts cover the response body, redirects are refused, and required fields/numbers are strictly validated.

```yaml
poolpilot:
  config-sources:
    application: order-service
    instance: ${HOSTNAME:order-local}
    interval-millis: 5000
    http:
      - id: central
        url: https://config.example.com/order-service.json
        bearer-token: ${CONFIG_SOURCE_TOKEN}
        timeout-millis: 3000
        format: DOCUMENT
```

No sources means no polling thread. Application, instance and source IDs use 1–64 ASCII letters/digits/dot/underscore/hyphen, starting with a letter/digit. Source IDs must be unique. Instance IDs are diagnostic; document application must match the configured application.

The publisher owns a monotonically increasing revision; expectedVersion comes from each pool's current local snapshot:

```json
{
  "schemaVersion": 1,
  "application": "order-service",
  "revision": 10,
  "updates": [
    {
      "pool": "order-io",
      "expectedVersion": 1,
      "reason": "Increase order processing capacity",
      "settings": {
        "coreSize": 4,
        "maxSize": 8,
        "queueCapacity": 64,
        "keepAliveSeconds": 60,
        "allowCoreTimeout": false,
        "rejectionPolicy": "ABORT",
        "slowTaskMillis": 1000
      }
    }
  ]
}
```

Accepted revisions are retained per source and pool. Old/duplicate documents are ignored; newer revisions still require a matching local version. A manual change causes a conflict instead of being overwritten. Review current settings and publish a higher revision with updated expectedVersion to reconcile it. Revision, configuration and audit are saved together; failed storage advances none. Persistence retains cursors across restarts; without persistence they reset with the process.

Pools apply independently; there is no cross-pool or distributed transaction. Instances with different local versions may diverge until explicitly reconciled. Disconnects preserve current state and retry on subsequent polls. Audit kind is EXTERNAL and actor is `config:<sourceId>`; this identifies the source, not the remote human publisher. Use separate state paths per instance. Query `/config-sources` for status and `/pools/{name}/sources` for accepted revisions.

For Nacos, store the same JSON and configure the full read URL and format:

| API             | format   | Example URL                                                                                                       |
| --------------- | -------- | ----------------------------------------------------------------------------------------------------------------- |
| v1 raw content  | DOCUMENT | `http://127.0.0.1:8848/nacos/v1/cs/configs?dataId=poolpilot.json&group=DEFAULT_GROUP`                             |
| v2 data string  | NACOS_V2 | `http://127.0.0.1:8848/nacos/v2/cs/config?dataId=poolpilot.json&group=DEFAULT_GROUP`                              |
| v3 data.content | NACOS_V3 | `http://127.0.0.1:8848/nacos/v3/admin/cs/config?dataId=poolpilot.json&groupName=DEFAULT_GROUP&namespaceId=public` |

Following the current [Nacos access credential guide](https://nacos.io/en/docs/latest/manual/user/auth/), prefer `bearer-token: ${NACOS_ACCESS_TOKEN}` for Authorization: Bearer. The access-token property sends an accessToken header; acceptance depends on the server version/auth plugin. Check older deployments' authentication contract or reuse a verified SDK through PoolConfigSource. Login/renewal is owned by the application/deployment; expiry is reported as a refresh failure. Shapes follow official [v1](https://nacos.io/en/docs/v1/open-api/), [v2](https://nacos.io/en/docs/v2/open-api/) and [v3 Admin API](https://nacos.io/en/docs/latest/manual/admin/admin-api/) documentation. Local verification uses contract fixtures, not a live Nacos server matrix.

A custom PoolConfigSource bean can reuse existing SDKs. Fetch must use bounded, interruptible I/O; close must return promptly and be idempotent. The framework cannot forcibly stop an uncooperative client.

## Admin identity

Shared tokens suit local/single-operator use. Named tokens cannot be combined with a shared token:

```yaml
poolpilot:
  admin:
    enabled: true
    token: ""
    users:
      - name: oncall
        token-sha256: ${POOLPILOT_ONCALL_TOKEN_SHA256}
        role: OPERATOR
        expires-at: 2027-01-01T00:00:00Z
      - name: observer
        token-sha256: ${POOLPILOT_OBSERVER_TOKEN_SHA256}
        role: READER
```

Tokens contain 12–256 visible ASCII characters without spaces. Generate high-entropy secrets with a cryptographic random source, then store the hexadecimal SHA-256 digest of UTF-8 token bytes. Do not use human passwords. There is no issuance/account-management endpoint. Up to 100 users require unique names and hashes; role defaults to READER, expiry is a UTC Instant. Digests use MessageDigest.isEqual. Restart the application to load changed credentials.

Readers can query all registered pools; every management mutation, including demo load, returns 403. The console shows identity/role and disables edit/rollback/load. Permissions apply to all pools, not per-pool ACLs. Shared tokens record token-admin. Caller-supplied user headers are not trusted identity.

With existing Spring Security/SSO use `authentication: APPLICATION`. Upstream authentication must establish a Servlet Principal and POOLPILOT_READ/POOLPILOT_WRITE role before the admin filter, which follows Spring Security's default servlet order. **Use current application session** uses the same-origin authenticated session. Cookie-based authentication requires upstream CSRF protection for writes. If customizing filter order, keep authentication before PoolPilot authorization.

An AdminAuthenticator bean can map another verified identity to AdminIdentity. Principal names must satisfy AdminIdentity length/character constraints. Protect Actuator separately, use TLS, and keep live secrets out of source, URLs and logs. See [SECURITY](../SECURITY.md).

The console reads the public `GET /poolpilot/api/auth` endpoint, which exposes only the authentication mode. TOKEN displays the token form; APPLICATION displays only "Use current application session". Allow the console assets and this metadata endpoint through upstream security; operational endpoints still require authentication and roles. PoolPilot does not provide an application sign-in page.
