# 治理配置

[简体中文](governance.md) · [English](governance.en.md)

## Spring @Async 与 CompletableFuture

在 YAML 中声明 `order-io` 池，然后将原有同名执行器 Bean 的创建方式改为下面的适配器。业务方法可继续使用 `@Async("orderExecutor")`，池由 registry 统一关闭。

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

`ManagedThreadPool.submitStage(Callable<T>)` 和该适配器的 `submitCompletable` 返回 CompletableFuture，同时保留原始 FutureTask 的业务异常与取消统计。取消排队 stage 会移除对应任务、释放队列容量；取消运行中的 stage 仅尝试中断。取消仍在运行、忽略中断的任务不会结束其超时跟踪。

原生 `CompletableFuture.supplyAsync(..., pool)` 可用于已有代码，但它内部捕获异常，池不能把所有业务失败归入 `failed`。需要可靠失败统计时使用 `submitStage`，或由结果回调记录业务指标。

基础 starter 默认组合 MDC 与应用提供的 `org.springframework.core.task.TaskDecorator` Bean，按照 Spring 顺序执行，MDC 位于最外层。若自行提供 core 的 `TaskDecorator`，需自行组合 MDC。装饰器必须在当前执行线程同步调用任务一次；数据库事务不跨线程传播。

## 排队与运行超时

```yaml
poolpilot:
  timeouts:
    enabled: true
    interval-millis: 250
    queue-millis: 10000
    run-millis: 30000
    max-tracked-tasks: 10000
```

基础 starter 默认开启；纯 core 默认关闭，可调用 `pool.setTimeoutPolicy(...)` 启用。阈值 0 关闭对应阶段，两个阈值均为 0 时不跟踪。每个任务在提交时捕获阈值，运行前标记开始时间，每个阶段至多产生一个超时事件。告警冷却仍按池与类型生效，超时计数不会因冷却而丢失。

检测依赖扫描，精度受到扫描周期和调度延迟影响，扫描前已完成的短暂越界可能不产生超时事件。事件包含任务 ID、池名、阶段、耗时、阈值、检测时配置版本和运行线程名；不采集业务参数、线程栈，不自动取消或中断任务。ID 在当前池生命周期内使用，不是分布式追踪 ID。

跟踪表按池有上限；超出时任务继续执行，增加 `poolpilot.tasks.tracking.dropped`。完成、拒绝、`remove`、`purge`、强制关闭和监控关闭会清理跟踪。取消排队 Future 后调用 `purge()` 可同时释放队列容量；直接修改 `getQueue()` 会绕过任务取消统计和生命周期管理。`taskTimeouts()` 消费尚未报告的事件，使用 starter 时由监控器统一调用。

额外指标：`poolpilot.tasks.queue.timeouts`、`poolpilot.tasks.run.timeouts`、`poolpilot.tasks.tracking.dropped`、`poolpilot.tasks.tracked`，均使用 `pool` 标签。

## 通知隔离

```yaml
poolpilot:
  alerts:
    notification-threads: 1
    notification-queue-capacity: 128
    notification-shutdown-millis: 1000
```

事件先存入最近 200 条告警，再提交独立通知队列。队列满时丢弃新投递，记录 `dropped`，管理台仍能查询该事件。监听器异常增加 `failed`，其后的监听器仍会执行。单个投递包含依次调用所有监听器；一个慢监听器会延迟同次投递的其他渠道，但不阻塞采样。

`GET /poolpilot/api/notifications` 返回 accepted、completed、failed、dropped、queued、active 与 closed。failed 统计监听器失败次数，completed 统计执行结束的投递，因此两者可同时增加。Micrometer 提供 `poolpilot.alerts.accepted / completed / failed / dropped / queued / active`。

关闭最多等待配置的毫秒数，随后中断活动通知并丢弃等待通知；不响应中断的监听器可能继续运行在 daemon 线程上。自定义网络通知应设置连接和读取超时。没有自动重试、持久补偿或送达保证。

## 外部配置与多实例

配置源通过独立调度线程轮询，默认间隔 5000 ms；最多 8 个源、每份文档最多 32 个池更新。HTTP 响应上限 1 MiB，请求超时覆盖响应体，拒绝重定向，严格检查数字与必需字段。

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

无配置源时不启动轮询。应用名与实例名限 1–64 个 ASCII 字母、数字、点、下划线或连字符，首字符为字母或数字。源 ID 使用相同规则且必须唯一。实例名用于状态诊断，不选择文档内容；应用名必须与文档一致。

远端文档由配置发布者维护 `revision`，必须单调递增；`expectedVersion` 来自各实例当前池快照。

```json
{
  "schemaVersion": 1,
  "application": "order-service",
  "revision": 10,
  "updates": [
    {
      "pool": "order-io",
      "expectedVersion": 1,
      "reason": "提高订单处理并行容量",
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

每个源、每个池保存已接受的修订号：旧或重复修订被忽略；新修订仍须通过本地版本检查。人工修改后发生冲突时不会覆盖，发布者需审阅当前配置、更新 expectedVersion 并发布更高 revision。修订号、参数与审计一同保存，写失败不推进任何一项。启用持久化后，重启可继续拒绝旧修订；关闭持久化时游标随进程重置。

同一文档中的池分别提交，不提供跨池或跨实例原子事务。不同本地版本的实例可能部分成功，需要读取各实例状态后重新协调。源断线保留当前配置，按轮询周期重试。审计类型为 `EXTERNAL`，操作人 `config:<sourceId>`；这是配置源身份，不能还原远端发布者。每个实例使用独立状态文件。状态接口为 `/config-sources`，每池已接受修订号接口为 `/pools/{name}/sources`。

Nacos 内容仍为上述 JSON，只需指定完整读取 URL 和响应格式：

| API                     | format   | URL 示例                                                                                                          |
| ----------------------- | -------- | ----------------------------------------------------------------------------------------------------------------- |
| v1，原始配置文本        | DOCUMENT | `http://127.0.0.1:8848/nacos/v1/cs/configs?dataId=poolpilot.json&group=DEFAULT_GROUP`                             |
| v2，data 为文本         | NACOS_V2 | `http://127.0.0.1:8848/nacos/v2/cs/config?dataId=poolpilot.json&group=DEFAULT_GROUP`                              |
| v3，data.content 为文本 | NACOS_V3 | `http://127.0.0.1:8848/nacos/v3/admin/cs/config?dataId=poolpilot.json&groupName=DEFAULT_GROUP&namespaceId=public` |

按 [Nacos 访问凭据](https://nacos.io/en/docs/latest/manual/user/auth/) 的建议，优先配置 `bearer-token: ${NACOS_ACCESS_TOKEN}`，以 Authorization: Bearer 请求头发送。`access-token` 配置发送 accessToken 请求头，是否接受该头取决于服务端版本与认证插件；旧版本需先核对认证约定，或通过 PoolConfigSource 接入已验证的 SDK。适配器不负责登录或令牌续期；凭据过期会计入同步失败，需由应用或部署系统更换。接口形状依据 [Nacos v1](https://nacos.io/en/docs/v1/open-api/)、[v2](https://nacos.io/en/docs/v2/open-api/) 与 [v3 Admin API](https://nacos.io/en/docs/latest/manual/admin/admin-api/)；本地使用契约测试，未运行真实 Nacos 服务端矩阵。

也可声明 `PoolConfigSource` Bean 接入现有客户端。fetch 必须使用有界、可中断 I/O，close 应快速、幂等返回；框架不能强制终止忽略中断的自定义客户端。

## 管理身份

共享令牌适合本机或单操作者。多人部署可用命名令牌，不能同时设置共享 `token`：

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

令牌是 12–256 位可见 ASCII 字符，不含空格。使用密码学随机源生成高熵令牌，再将 UTF-8 内容的 SHA-256 十六进制摘要放入配置；不要把人类密码当令牌。服务端不提供令牌生成、发放或账户管理接口。最多 100 个用户，名字与摘要必须唯一，角色默认 READER，过期时间使用 UTC Instant。摘要比较使用 MessageDigest.isEqual。应用重启加载凭据；不支持在线凭据修改。

只读账号可访问所有查询，任何管理写入（含 demo 负载）返回 403。界面显示用户名与角色，并禁用编辑、回滚和提交负载。角色作用于全部注册池，不做逐池权限划分；共享令牌始终记录 `token-admin`。认证身份不从调用方自报的用户请求头获取。

已有 Spring Security／SSO 时配置 `authentication: APPLICATION`，上游认证必须先建立 Servlet Principal 与 `POOLPILOT_READ` 或 `POOLPILOT_WRITE` 角色。默认过滤器位于 Spring Security 默认 Servlet 过滤器之后。页面只显示“使用当前登录身份”，隐藏令牌输入框，并沿用同源已登录身份。应用安全配置应允许访问管理台静态资源及 `GET /poolpilot/api/auth`，后者只公开认证模式；其余管理端点仍需认证和授权。使用 Cookie 的应用需由上游安全配置保护写入的 CSRF。自定义安全过滤器顺序时，确保先完成认证，再执行 PoolPilot 授权。

也可提供 `AdminAuthenticator` Bean，将已验证的应用身份映射为 `AdminIdentity`。Principal 的名字须符合 AdminIdentity 的长度与字符约束。部署时保护 Actuator，使用 TLS，避免在仓库、URL、日志中放入真实密钥。安全问题报告见 [SECURITY](../SECURITY.md)。
