# 与 dynamic-tp、Hippo4j 的源码对比

[简体中文](comparison.md) · [English](comparison.en.md)

日期：2026-10-08。PoolPilot 版本：0.1.0。

PoolPilot 可以在单个 Spring Boot 应用中完成运行时调参、版本冲突控制、配置恢复、回滚、任务统计和 Prometheus 接入。提供显式 Spring @Async 适配、HTTP/Nacos 配置刷新、未完成任务超时发现、独立通知队列与命名身份。范围侧重 Java 17/21、Boot 3；参考项目仍有更广的旧版本与第三方执行器适配生态。

## 检查范围

拉取仓库后检查父 POM、自动配置、Bean 后处理器、执行器、队列、配置刷新、告警、指标和测试源码。以下链接固定到所检查的提交，不能代替各项目的发布版兼容性矩阵。

| 项目       | 检查提交                                                                                        | 提交日期   | 父 POM 版本                  |
| ---------- | ----------------------------------------------------------------------------------------------- | ---------- | ---------------------------- |
| dynamic-tp | [e304f183](https://github.com/dromara/dynamic-tp/tree/e304f1832db7afc2a83bd7440b1b95018c0562c5) | 2026-08-14 | 1.2.2                        |
| Hippo4j    | [c734d00e](https://github.com/opengoofy/hippo4j/tree/c734d00ec1e560fb63570220eee708c7440496fd)  | 2026-03-12 | 2.0.0-SNAPSHOT，develop 分支 |

参考项目没有在本次检查中构建或压测；实现差异来自源码阅读，性能数字只来自 PoolPilot 的独立 JMH 实验。

## 兼容性与接入成本

| 方面               | PoolPilot                                          | 参考源码及影响                                                                                                                                                                                                                                                       |
| ------------------ | -------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Java / Spring 基线 | Java 17/21，Boot 3.4.13/3.5.16；测试矩阵见验证记录 | dynamic-tp 父 POM 的 source/target 为 8，Boot BOM 为 2.7.18；Hippo4j 父 POM 为 Java 8，并有 Boot 3 专用 Web 适配配置。这说明兼容路径更广，不能据此断言每个模块支持全部版本。                                                                                         |
| 已有执行器         | registry 池与显式 `PoolPilotTaskExecutor` / @Async | dynamic-tp 适配标注过的 JUC / Spring 执行器；Hippo4j 处理自己的 DynamicThreadPoolExecutor，并适配 Spring、TTL、Zipkin 包装。PoolPilot 可保留命名 @Async 调用，但需替换对应 Bean 的创建方式，不自动接管第三方实例。参考实现也有注解与类型条件，并非无条件接管所有池。 |
| Web 与监控依赖     | 基础 starter 不传递 MVC，admin starter 可选        | 参考项目将 core、配置源、监控与适配器分模块。PoolPilot 已拆分管理台/API，后台任务应用只引入基础 starter；Actuator 为指标自动配置提供支持。                                                                                                                           |
| 上下文传播         | 默认 MDC + 有序 Spring TaskDecorator 组合          | dynamic-tp 可配置多个包装器；Hippo4j 有 TTL、Zipkin 等适配。PoolPilot 组合应用的 Spring 装饰器；SecurityContext/TTL 等需应用提供对应装饰器。替换 core TaskDecorator 时仍需自行保留 MDC。                                                                             |

源码：[dynamic-tp POM](https://github.com/dromara/dynamic-tp/blob/e304f1832db7afc2a83bd7440b1b95018c0562c5/pom.xml)、[DtpPostProcessor](https://github.com/dromara/dynamic-tp/blob/e304f1832db7afc2a83bd7440b1b95018c0562c5/spring/src/main/java/org/dromara/dynamictp/spring/DtpPostProcessor.java)、[Hippo4j 后处理器](https://github.com/opengoofy/hippo4j/blob/c734d00ec1e560fb63570220eee708c7440496fd/starters/threadpool/config/src/main/java/cn/hippo4j/config/springboot/starter/support/DynamicThreadPoolPostProcessor.java)、[Boot 3 Web 适配](https://github.com/opengoofy/hippo4j/blob/c734d00ec1e560fb63570220eee708c7440496fd/starters/threadpool/adapter/web/src/main/java/cn/hippo4j/springboot3/starter/adapter/web/WebAdapterConfiguration.java)。

本项目：[starter POM](../poolpilot-spring-boot-starter/pom.xml)、[自动配置](../poolpilot-spring-boot-starter/src/main/java/dev/poolpilot/spring/PoolPilotAutoConfiguration.java)。

## 可用性与故障处理

### 超时发现

PoolPilot 保留完成时慢任务统计，并独立扫描有界的排队/运行任务跟踪表。未结束任务可产生 QUEUE_TIMEOUT/RUN_TIMEOUT，带任务标识、阈值、耗时与工作线程名；每阶段最多一次。超出跟踪容量增加 dropped 计数，拒绝、完成、取消清理与关闭释放跟踪。默认仅观测，不采集线程栈或中断任务。

dynamic-tp 的 `TaskTimeoutAware` 在提交时安排排队超时，在执行前取消排队定时器并安排运行超时；完成后取消运行定时器。`RunTimeoutTimerTask` 能在任务未结束时告警、输出线程栈，并按配置尝试中断。这种逐任务定时器方案与 PoolPilot 的周期扫描不同；扫描精度受周期和调度影响，完成前未扫描的短暂越界可能漏检。两者均不能承诺终止忽略中断的任务。

Hippo4j 此提交的 `TaskTimeoutNotifyAlarmPlugin` 继承 `AbstractTaskTimerPlugin`，由 `afterExecute` 调用耗时检查，属于完成后超时通知。它与 dynamic-tp 的在途定时器机制不同。

源码：[TaskTimeoutAware](https://github.com/dromara/dynamic-tp/blob/e304f1832db7afc2a83bd7440b1b95018c0562c5/core/src/main/java/org/dromara/dynamictp/core/aware/TaskTimeoutAware.java)、[RunTimeoutTimerTask](https://github.com/dromara/dynamic-tp/blob/e304f1832db7afc2a83bd7440b1b95018c0562c5/core/src/main/java/org/dromara/dynamictp/core/timer/RunTimeoutTimerTask.java)、[Hippo4j 计时插件](https://github.com/opengoofy/hippo4j/blob/c734d00ec1e560fb63570220eee708c7440496fd/threadpool/core/src/main/java/cn/hippo4j/core/executor/plugin/impl/AbstractTaskTimerPlugin.java)。

### 告警通知与关闭

PoolPilot 将采样与通知放在独立线程，通知队列有界，饱和丢弃新投递并计数。事件先写入告警列表；失败监听器不跳过后续监听器，关闭等待有明确期限，不依赖采样锁。阻塞监听器、饱和和关闭测试验证了隔离效果。TaskObserver 仍在任务完成线程执行，耗时回调会占用工作线程。

dynamic-tp 的 `AlarmManager` 把告警检查与通知交给独立执行器，队列容量 2000，使用 DiscardOldest 策略。它隔离慢通知，但队列满时可能丢弃旧通知，并不保证送达。PoolPilot 使用丢弃新通知策略，API/Micrometer 显示接受、完成、失败、丢弃与待处理数；两种策略都不保证送达。

源码：[PoolAlertMonitor](../poolpilot-spring-boot-starter/src/main/java/dev/poolpilot/spring/PoolAlertMonitor.java)、[AlarmManager](https://github.com/dromara/dynamic-tp/blob/e304f1832db7afc2a83bd7440b1b95018c0562c5/core/src/main/java/org/dromara/dynamictp/core/notifier/manager/AlarmManager.java)。

### 配置传播与恢复

PoolPilot 使用本地 `PoolStateStore` 保存完整配置、版本和最近 200 条变更。同名池重启恢复、写失败不推进版本、过期修改返回 409、回滚追加新版本，已有测试验证。文件锁限制一个路径对应一个 registry，不能用共享文件实现多实例广播。

dynamic-tp 的 Nacos refresher 监听配置变更事件并刷新环境；Hippo4j 的 refresher 解析、绑定配置并发布刷新事件。Hippo4j server 另有向客户端地址下发变更的实现。PoolPilot 提供 PoolConfigSource SPI、HTTP/Nacos 有界轮询与应用/实例标识。远端 revision 由发布者单调维护，本地 expectedVersion 防止覆盖人工修改。接受的修订号与配置一起持久化，断线保留现状，重连忽略旧修订；文档中的池独立提交，不提供跨实例原子事务或中心化集群控制台。

持久化目前在 registry 和池配置锁内同步序列化、写文件并执行 `force(true)`。任务提交继续，但完整快照、其他治理请求和关闭可能等待存储。异步落盘会改变“写失败则调参不生效”的约定，需先定义恢复一致性，不能只把写操作移出锁。

源码：[ManagedPoolRegistry](../poolpilot-core/src/main/java/dev/poolpilot/core/ManagedPoolRegistry.java)、[FilePoolStateStore](../poolpilot-spring-boot-starter/src/main/java/dev/poolpilot/spring/FilePoolStateStore.java)、[NacosRefresher](https://github.com/dromara/dynamic-tp/blob/e304f1832db7afc2a83bd7440b1b95018c0562c5/starter/starter-configcenter/starter-nacos/src/main/java/org/dromara/dynamictp/starter/nacos/refresher/NacosRefresher.java)、[Hippo4j refresher](https://github.com/opengoofy/hippo4j/blob/c734d00ec1e560fb63570220eee708c7440496fd/starters/threadpool/config/src/main/java/cn/hippo4j/config/springboot/starter/refresher/AbstractConfigThreadPoolDynamicRefresh.java)。

### 访问控制

PoolPilot 管理 API 默认关闭。共享令牌保留 token-admin 语义；命名令牌用 SHA-256 摘要恒定时间比较，支持 READER/OPERATOR 与过期时间，审计使用认证用户名。APPLICATION 模式接入已有 Principal/角色，AdminAuthenticator SPI 可连接其他已验证身份，不信任自报用户请求头。Hippo4j server 的 JWT 过滤器接入 Spring Security 的认证上下文与用户上下文，提供了更完整的身份接入路径；仅凭过滤器不能推断所有接口的授权范围。

命名身份按全部注册池划分读写，不提供逐池 ACL 或账号发放服务。Actuator、TLS 与 Cookie 认证的 CSRF 由应用保护；SSO 的验证和会话管理仍由现有认证体系负责。

源码：[管理鉴权](../poolpilot-spring-boot-admin-starter/src/main/java/dev/poolpilot/spring/PoolPilotAdminAutoConfiguration.java)、[JWTAuthorizationFilter](https://github.com/opengoofy/hippo4j/blob/c734d00ec1e560fb63570220eee708c7440496fd/threadpool/server/auth/src/main/java/cn/hippo4j/auth/filter/JWTAuthorizationFilter.java)。

## 性能与资源使用

### 指标读取

旧采集路径为每个 FunctionCounter 调用 `snapshot()`，重复取得池配置锁、读取 JDK 工作线程状态、分配快照和任务统计对象；样本更新时还会触发百分位排序。`PoolMetricsBinder` 现改用 `taskCount(TaskCounter)` 读取对应 LongAdder，队列容量和版本也直接读取。指标名称和计数含义保留。

新路径不依赖完整配置快照。回归测试阻塞状态写入后，验证计数器、版本和容量仍可读取。各读数是单独采样，不能作为跨字段一致的配置事务快照；JDK 活跃线程与队列长度等 Gauge 仍使用各自的锁。

JMH 对比七个计数器的旧、新读取路径，并比较同一动态队列上 JDK executor 与 ManagedThreadPool 的单次 `submit/get`。实验条件、原始 JSON、误差和测量范围见 [基准说明](../benchmarks/README.md)。还提供 4 生产者/4 消费者队列竞争、半数取消时的过滤算法与短任务 SampleTime 尾延迟。没有测量参考框架，也不代表完整 scrape、治理请求或业务吞吐量。

源码：[PoolMetricsBinder](../poolpilot-spring-boot-starter/src/main/java/dev/poolpilot/spring/PoolMetricsBinder.java)、[TaskMetrics](../poolpilot-core/src/main/java/dev/poolpilot/core/TaskMetrics.java)、[回归测试](../poolpilot-core/src/test/java/dev/poolpilot/core/RegistryStateTest.java)。

### 队列与任务观测

PoolPilot 的 ArrayDeque 队列用一把锁保护读、写和扩缩容。参考项目的可变容量 LinkedBlockingQueue 使用独立 put/take 锁与原子计数，可能允许生产者和消费者并行，但增加链表节点分配。两种取舍不能从锁数量直接推导吞吐量；需要多生产者、多消费者、不同任务耗时和队列长度的竞争测试。

PoolPilot 每个任务创建 TimedTask 和 TaskSample；样本写入使用同步锁，窗口固定 256。高频短任务应检查包装分配、样本锁竞争和 Micrometer Timer 成本。dynamic-tp 的 LimitedUniformReservoir 容量为 4096，随机抽样并按监控区间重置；Hippo4j Micrometer handler 更新缓存对象供 Gauge 使用。采样语义和新鲜度不同，不能把各项目的 P95 或单次读数直接对齐。

`purge()` 现在使用单次持锁过滤，线性扫描，解锁后记录取消，保留队列顺序、单次取消计数与生产者唤醒；行为测试与独立过滤算法基准覆盖此路径。可配置的百万级容量是参数上限，不是内存使用建议。

源码：[PoolPilot 队列](../poolpilot-core/src/main/java/dev/poolpilot/core/ResizableCapacityBlockingQueue.java)、[dynamic-tp 队列](https://github.com/dromara/dynamic-tp/blob/e304f1832db7afc2a83bd7440b1b95018c0562c5/common/src/main/java/org/dromara/dynamictp/common/queue/VariableLinkedBlockingQueue.java)、[LimitedUniformReservoir](https://github.com/dromara/dynamic-tp/blob/e304f1832db7afc2a83bd7440b1b95018c0562c5/core/src/main/java/org/dromara/dynamictp/core/metric/LimitedUniformReservoir.java)、[Hippo4j Micrometer handler](https://github.com/opengoofy/hippo4j/blob/c734d00ec1e560fb63570220eee708c7440496fd/threadpool/monitor/micrometer/src/main/java/cn/hippo4j/monitor/micrometer/DynamicThreadPoolMicrometerMonitorHandler.java)。

## 实现与验证

| 项目 | 工作                                | 验证内容                                                                               |
| ---- | ----------------------------------- | -------------------------------------------------------------------------------------- |
| 1    | 通知与采样隔离                      | 阻塞监听器不延迟后续采样；队列有界；拒绝/失败可观测；关闭在期限内返回                  |
| 2    | 排队与在途运行超时                  | 阻塞未完成任务能告警；结束/取消/拒绝后清理跟踪；不重复触发；只观测，不自动中断         |
| 3    | 可选管理模块与 Spring executor 接入 | 后台应用不传递 MVC；支持显式指定的 `@Async` 执行器；保持 Spring 生命周期和上下文装饰器 |
| 4    | 队列竞争、取消清理和短任务实验      | 多生产者负载、GC 与尾延迟可复现；优化前后保留相同取消和缩容行为                        |
| 5    | 配置源与真实身份审计                | 重连与过期更新不覆盖新配置；记录真实操作者；多实例协议有明确版本来源                   |

验证源码：[通知隔离](../poolpilot-spring-boot-starter/src/test/java/dev/poolpilot/spring/AlertDeliveryTest.java)、[任务超时](../poolpilot-core/src/test/java/dev/poolpilot/core/TaskTimeoutTest.java)、[Spring Async](../poolpilot-spring-boot-starter/src/test/java/dev/poolpilot/spring/AsyncIntegrationTest.java)、[配置传播](../poolpilot-spring-boot-starter/src/test/java/dev/poolpilot/spring/ConfigSourceTest.java)、[身份权限](../poolpilot-spring-boot-admin-starter/src/test/java/dev/poolpilot/spring/AdminAuthenticationTest.java)、[性能实验](../benchmarks/README.md)。

Java 8 / Boot 2 支持需要单独的编译与依赖方案；改一个 POM 不能让 records、switch 表达式和 Jakarta API 自动兼容。支持范围应由兼容性测试确定。单实例应用可使用现有治理能力；需要集群控制台和完整适配生态的应用，应评估参考框架现有模块。
