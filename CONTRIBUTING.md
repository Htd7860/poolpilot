# 贡献指南

先用 Java 17 执行 mvnw verify。修改队列、调参、任务生命周期时提供能失败的复现与能验证修复的测试。测试优先使用 latch / barrier，不依赖固定 sleep 猜测线程调度。

新功能先说明真实场景、已有实现的不足、兼容性与资源边界。保持 core 不依赖 Spring；新通知渠道实现 PoolAlertListener。不要把 traceId / 用户 ID 放进 Micrometer 标签。

PR 应说明问题触发条件、最终行为、测试和已知限制。性能结论附机器、JDK、负载、原始结果与重复实验。

性能实验使用独立 [JMH 工程](benchmarks/README.md)，保持比较双方的队列、线程数和负载一致。浏览器中的演示负载只验证功能。

界面修改运行 `npm ci` 和 `npm run test:browser`，使用独立 demo；新增文案同步添加英文，切换不能丢失输入或改变用户填写的原因。不要提交 target、node_modules、本地令牌或环境文件。

## English

Run Maven `verify` with JDK 17. Changes to queues, configuration or lifecycle should include a failing reproduction and a meaningful regression test. Prefer latches / barriers over fixed sleeps.

Describe the real use case, compatibility and resource limits. Keep core independent of Spring. Implement PoolAlertListener for notifications, and keep trace / user identifiers out of metric tags.

For UI changes, run `npm ci` and `npm run test:browser` against a disposable demo. Add English translations, preserve input and keep user-written reasons unchanged. Do not commit generated directories, tokens or environment files.

PR descriptions should state the trigger, resulting behavior, validation and limits. Performance claims require hardware, JDK, workload, raw results and repeated runs.

Use the separate [JMH project](benchmarks/README.md) for performance experiments. Keep queue, thread counts and workloads equivalent across comparisons. Console demo loads are functional checks.
