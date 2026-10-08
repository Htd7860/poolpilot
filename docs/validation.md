# 测试与基准 / Validation

日期 / Date: 2026-10-08。版本 / Version: 0.1.0。结构化结果见 [validation-results.json](validation-results.json)。

## 环境 / Environment

Windows，Oracle JDK 17+35、Eclipse Temurin 21.0.12.1+1、Maven Wrapper 3.9.16。浏览器 Chrome 154，Playwright 1.64.0。

Windows, Oracle JDK 17+35, Eclipse Temurin 21.0.12.1+1 and Maven Wrapper 3.9.16. Chrome 154 / Playwright 1.64.0 provide browser checks.

## 兼容性 / Compatibility

| Java | Spring Boot | Tests | Failures / errors / skips | Build                                              |
| ---- | ----------- | ----: | ------------------------- | -------------------------------------------------- |
| 17   | 3.5.16      |    73 | 0 / 0 / 0                 | clean verify                                       |
| 21   | 3.5.16      |    73 | 0 / 0 / 0                 | verify，独立源码包 / independent source extraction |
| 17   | 3.4.13      |    73 | 0 / 0 / 0                 | clean verify                                       |
| 21   | 3.4.13      |    73 | 0 / 0 / 0                 | clean verify                                       |

使用 -Dspring-boot.version=3.4.13 切换 BOM 与 Boot 插件；默认 3.5.16。Java 编译目标始终为 17。CI 配置 Linux/Windows、Java 17/21 与这两组 Boot 版本。

Use -Dspring-boot.version=3.4.13 to override both BOM and Boot plugin; default is 3.5.16. Bytecode targets Java 17. CI defines Linux/Windows × Java 17/21 × both Boot versions.

| Module        | Tests | Coverage                                                                                                                                                                                                                             |
| ------------- | ----: | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| core          |    32 | 扩缩容、阻塞/中断、版本冲突、Future 与 stage 异常/取消、关闭、状态恢复、外部修订号、任务超时和批量过滤 / resize, blocking, versions, task outcomes, stage cancellation, recovery, source revisions, timeouts and bulk filtering      |
| base starter  |    17 | 无 MVC 接入、@Async、MDC/装饰器、告警冷却、通知阻塞与丢弃、存储、HTTP/Nacos 契约、双实例重连和配置冲突 / non-MVC Async, decorators, alerts, bounded delivery, storage, source contracts, reconnection and conflicts                  |
| admin starter |     6 | 不安全凭据启动失败、命名身份、过期、读写权限、Principal 角色、自报身份拒绝、保存失败 503 / credential validation, identities, expiry, roles, verified principals, spoof rejection and failed persistence                             |
| demo          |    18 | 真实 HTTP 鉴权、调参与回滚审计、409、数字校验、Prometheus、双语错误、未完成任务超时与关闭管理后的负载接口移除 / HTTP authorization, audit, conflicts, validation, metrics, localization, unfinished timeouts and disabled management |

并发测试使用 latch 与线程状态控制时序。批量清理测试先确认生产者已在满队列上等待，再验证删除唤醒。阻塞状态保存时仍可读取直接计数器并发现运行超时。HTTP 集成启用真实 Prometheus registry。

Latches and thread-state checks establish concurrency conditions. Bulk filtering wakes an already-waiting producer; direct counters and timeout detection continue during blocked storage. HTTP integration uses a real Prometheus registry.

## 浏览器 / Browser

打包的 Boot 3.5.16 demo 通过 npm run test:browser：15 项操作、12 项语言/导航、4 项治理权限检查，共 31 项。测试在关闭持久化的独立实例上执行；命名账号检查自行启动临时端口实例并关闭它。

The packaged Boot 3.5.16 demo passed npm run test:browser: 15 operational, 12 bilingual/navigation and 4 governance checks (31 total). Persistence is disabled; named-user tests start and stop their own ephemeral-port server.

检查包括认证模式入口、未登录应用身份拒绝、令牌不落浏览器存储、查询/分页、调参冲突与刷新、失败任务、告警、histogram、回滚预览与来源审计、语言切换保留输入、键盘菜单、390px 各页面、只读按钮与 API 403、真实操作人、超时筛选及手机顶部控件不重叠。未捕获脚本异常为 0。

Coverage includes authentication-mode entries, rejected unauthenticated application sessions, storage isolation, queries/pagination, conflicts/refresh, failures/alerts, histograms, rollback review/audit, preserved bilingual input, keyboard navigation, 390px views, reader controls/HTTP 403, individual actors, timeout filtering and non-overlapping mobile header controls. No uncaught script errors were observed.

## 性能 / Performance

JMH 执行 20 组：7 组读取/提交、6 组 4 生产者/4 消费者队列竞争、4 组取消过滤、3 组短任务 SampleTime。每组 2 forks、2 次预热与 3 次测量，每次 1 秒；前 17 组含 GC profiler。原始 JSON、负载条件、误差与解释范围见 [JMH](../benchmarks/README.md)。

JMH ran 20 cases: seven reads/submissions, six queue-contention cases, four cancellation-filter cases and three sampled-tail cases. Two forks, two warmups and three measurements of one second; the first 17 cases use GC profiling. See raw JSON, conditions/errors and limits in [JMH](../benchmarks/README.md).

## 发布包 / Packaging

源码 ZIP 保留 Wrapper 执行权限，排除 Git、构建输出、Node 依赖、运行状态、环境文件、日志、锁与临时文件。必要模块、源码/测试/配置/页面与校验值已检查；源码可在独立目录构建，最终代码与验证目录一致。本地文档链接检查通过。复现与附件命令见 [发布说明](releasing.md)。

The source ZIP retains Wrapper permissions and excludes Git/build output, Node dependencies, state, environment files, logs, locks and temporary files. Required modules, source/tests/configuration/UI and hashes are checked. Sources build independently and final code matches the verified directory. Local documentation links pass. See [releasing](releasing.md).

## 验证范围 / Limits

上述记录来自 Windows 本地执行。Linux/Windows 的远程矩阵状态见 [GitHub Actions](https://github.com/Htd7860/poolpilot/actions)。Docker、真实 Nacos 服务端版本矩阵及长期生产负载未执行；Nacos 使用官方响应形状的 HTTP 契约测试。微基准不比较参考框架，不代表整体业务性能。外部更新按池与实例独立提交，没有分布式原子事务或中心化集群控制台。

The results above come from local Windows runs. See [GitHub Actions](https://github.com/Htd7860/poolpilot/actions) for the remote Linux/Windows matrix. Docker, live Nacos server matrices and sustained production loads have not run; Nacos uses HTTP contract fixtures following official response shapes. Benchmarks do not compare reference frameworks or establish whole-application performance. External updates commit independently per pool/instance without a distributed transaction or central cluster console.
