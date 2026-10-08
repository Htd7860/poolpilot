# JMH 基准 / Benchmarks

独立 Maven 工程，不参与默认应用构建，使用 [OpenJDK JMH](https://github.com/openjdk/jmh)。管理台演示负载用于功能验收，不能推算生产吞吐量。

This standalone project uses OpenJDK JMH outside the application reactor. Console loads are functional checks, not throughput measurements.

## 复现 / Reproduce

需要完整 JDK 17 或 21；Windows 将 ./mvnw 换成 .\mvnw.cmd。先安装父 POM 与 core，再构建基准。

Use a full JDK 17/21; Windows uses .\mvnw.cmd. Install the parent/core before building benchmarks.

```bash
./mvnw -B -ntp -pl poolpilot-core -am install
./mvnw -B -ntp -f benchmarks/pom.xml package
java -jar benchmarks/target/benchmarks.jar PoolBenchmark -f 2 -wi 2 -i 3 -w 1s -r 1s -prof gc -rf json -rff benchmarks/results/local-reads.json
java -jar benchmarks/target/benchmarks.jar 'QueueBenchmark|PurgeBenchmark' -f 2 -wi 2 -i 3 -w 1s -r 1s -prof gc -rf json -rff benchmarks/results/local-queues.json
java -jar benchmarks/target/benchmarks.jar 'PoolBenchmark.*Submit' -bm sample -f 2 -wi 2 -i 3 -w 1s -r 1s -rf json -rff benchmarks/results/local-tails.json
```

记录环境：2026-10-08，Windows，Intel Core i7-14650HX（16 核 / 24 逻辑处理器），Oracle HotSpot JDK 17+35，JMH 1.37，默认 JVM 参数，无 CPU 绑定。每组 2 forks、2 次预热、3 次测量，每次 1 秒。平均误差为 JMH 99.9% 置信区间半宽；笔记本调度与电源状态影响重复结果。生产决策应延长测量并使用实际负载。

Recorded on Windows, Intel Core i7-14650HX (16 cores/24 logical processors), Oracle HotSpot JDK 17+35, JMH 1.37, default JVM flags, no CPU pinning. Two forks, two warmups and three measurements of one second each. Mean errors are JMH 99.9% confidence interval half-widths. Laptop scheduling/power affect reproducibility; use longer trials and real workloads for deployment decisions.

## 指标读取与提交 / Reads and submissions

读取使用 4/32 个预启动空闲线程，先完成 256 个任务并缓存百分位，测量期间不写任务。snapshotCounters 复现七次完整快照，directCounters 读取相同七个 LongAdder。

Read trials use 4/32 prestarted idle workers and 256 seeded samples with cached percentiles, without concurrent task writes. Seven full snapshots are compared with seven direct LongAdder reads.

提交使用同一种动态队列（容量 1024）、4 个预启动 worker、一个提交线程、返回 42 的任务，每次 submit 后等待 get。managedSubmit 关闭超时跟踪，trackedSubmit 开启有界跟踪；无 MDC、Micrometer 或通知监听器。没有饱和、I/O 或多提交者竞争。

Submissions use the same resizable queue (capacity 1024), four prestarted workers and one submit/get thread with one outstanding task returning 42. Managed tracking is disabled/enabled in managedSubmit/trackedSubmit respectively, without MDC, Micrometer or notifications. These are not saturation, I/O or concurrent-submitter trials.

原始结果 / Raw: [windows-jdk17.json](results/windows-jdk17.json).

| 操作 / Operation | Workers | Mean ± error (µs/op) | Allocation (B/op) |
| ---------------- | ------: | -------------------: | ----------------: |
| directCounters   |       4 |      0.0047 ± 0.0018 |             0.000 |
| directCounters   |      32 |      0.0041 ± 0.0002 |             0.000 |
| managedSubmit    |       4 |      7.4748 ± 0.7634 |           189.332 |
| plainSubmit      |       4 |      7.1301 ± 0.7324 |            77.328 |
| snapshotCounters |       4 |      0.7471 ± 0.0233 |          1792.000 |
| snapshotCounters |      32 |      1.5323 ± 0.0654 |          1792.001 |
| trackedSubmit    |       4 |      7.7677 ± 0.4891 |           261.331 |

直接读取减少这段采集路径的时间与分配；不代表整个服务、HTTP 或 Prometheus scrape 的加速倍数。提交结果的置信区间重叠，不能据此认定延迟差异显著。

Direct reads reduce this collection path's time/allocation; no whole-service, HTTP or scrape speedup is inferred. Submission confidence intervals overlap and do not establish a significant latency difference.

## 队列竞争 / Queue contention

4 个生产者调用非阻塞 offer，4 个消费者调用 poll，容量 64/1024，比较 PoolPilot、JDK ArrayBlockingQueue 与 LinkedBlockingQueue。JMH 主分数包含失败入队与空队列尝试；不能将其当作任务吞吐量。下表按每次迭代的尝试速率乘以成功入队占比估算成功入队速率，再取六次均值。原始 accepted/received/full/empty 次数与 GC 指标均保留。

Four producers call nonblocking offer and four consumers poll at capacity 64/1024. The primary score includes failed/empty attempts, not useful task throughput. Estimated accepted rates multiply each iteration's attempt rate by its accepted fraction, then average six iterations. Raw accepted/received/full/empty counts and GC metrics are retained.

原始结果 / Raw: [windows-jdk17-queues.json](results/windows-jdk17-queues.json).

| Queue     | Capacity | Estimated accepted (million/s) | Failed/empty attempts (%) | Allocation per attempt (B) |
| --------- | -------: | -----------------------------: | ------------------------: | -------------------------: |
| POOLPILOT |       64 |                          3.239 |                      86.0 |                      0.160 |
| ARRAY     |       64 |                          3.870 |                      83.0 |                      0.183 |
| LINKED    |       64 |                          3.517 |                      99.0 |                      0.156 |
| POOLPILOT |     1024 |                         18.862 |                      15.8 |                      0.162 |
| ARRAY     |     1024 |                         16.082 |                      20.5 |                      0.143 |
| LINKED    |     1024 |                          9.795 |                      81.2 |                      2.660 |

该实验表明成功率和容量会明显改变结论；双锁队列的快速失败路径可能产生很高的尝试分数。空任务交换不能代表 ThreadPoolExecutor 的线程增长、背压或业务处理。队列继续保留一把锁和 ArrayDeque，避免仅凭锁数量更换实现。

Success rates/capacity materially change interpretation; fast failure paths in split-lock queues can yield large attempt scores. Empty queue exchanges do not represent worker growth, backpressure or business work. The implementation retains its single-lock ArrayDeque rather than changing it based on lock counts.

## 取消过滤 / Cancellation filtering

固定队列含 1000/10000 个 FutureTask，交替取消半数；比较快照遍历逐个 remove 与单次持锁过滤。每次调用前在 Level.Invocation 重新填充，填充时间不计入操作时间；GC profiler 可能包含准备工作。这里测量队列过滤算法，不含 ManagedThreadPool 的取消记录、任务执行或并发取消成本。

A queue holds 1000/10000 FutureTasks, half cancelled alternately. Snapshot iteration/individual remove is compared with one locked filter. Level.Invocation setup refills the queue outside operation timing; GC profiling may include fixture work. This measures filtering, excluding managed cancellation recording, execution and concurrent cancellation costs.

| Algorithm         | Tasks | Mean ± error (µs/op) |
| ----------------- | ----: | -------------------: |
| bulkRemoval       |  1000 |        5.569 ± 4.236 |
| bulkRemoval       | 10000 |      40.282 ± 21.140 |
| individualRemoval |  1000 |       99.434 ± 2.454 |
| individualRemoval | 10000 |    4063.436 ± 83.137 |

单次过滤减少反复扫描，在该取消分布下耗时显著降低。功能测试另行验证剩余顺序、生产者唤醒、单次取消计数和缩容保留任务。

The bulk filter avoids repeated scans and substantially reduces time for this cancellation distribution. Separate behavioral tests verify remaining order, producer wakeups, once-only cancellation counting and task retention on shrink.

## 短任务尾延迟 / Short-task tails

同样的单提交者负载使用 JMH SampleTime，独立于 AverageTime 实验运行。采样与统计本身影响分布；P95/P99 不是置信区间，也不是业务请求端到端延迟。

The same single-submitter workload runs separately with SampleTime. Sampling changes the observed distribution; P95/P99 are not confidence intervals or end-to-end request latency.

原始结果 / Raw: [windows-jdk17-tails.json](results/windows-jdk17-tails.json).

| Operation     | Mean (µs) | P95 (µs) | P99 (µs) |
| ------------- | --------: | -------: | -------: |
| managedSubmit |    12.171 |   20.192 |   34.496 |
| plainSubmit   |    12.302 |   18.080 |   28.992 |
| trackedSubmit |    13.729 |   20.800 |   36.215 |

未测量参考框架性能、完整 Micrometer Timer 成本、持久化 I/O、长期生产负载或虚拟线程。这些数字用于复现具体路径与资源取舍，不能承诺不同机器或业务具有相同表现。

Reference frameworks, full Timer overhead, persistence I/O, sustained production load and virtual threads are outside these trials. These figures reproduce specific paths and tradeoffs, not guarantees for other machines/workloads.
