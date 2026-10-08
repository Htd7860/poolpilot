package dev.poolpilot.core;

/** Full configuration replacement; queue shrink preserves already queued tasks. */
public record PoolSettings(
    int coreSize,
    int maxSize,
    int queueCapacity,
    long keepAliveSeconds,
    boolean allowCoreTimeout,
    RejectionPolicy rejectionPolicy,
    long slowTaskMillis) {
  public PoolSettings {
    if (coreSize < 0 || maxSize < 1 || coreSize > maxSize || maxSize > 1024)
      throw new IllegalArgumentException("线程数必须满足 0 ≤ coreSize ≤ maxSize ≤ 1024，maxSize 至少为 1");
    if (queueCapacity < 1 || queueCapacity > 1_000_000)
      throw new IllegalArgumentException("queueCapacity 必须在 1 到 1000000 之间");
    if (keepAliveSeconds < 0
        || keepAliveSeconds > 86400
        || (allowCoreTimeout && keepAliveSeconds == 0))
      throw new IllegalArgumentException("keepAliveSeconds 必须在 0 到 86400 之间；核心线程超时时必须大于 0");
    if (rejectionPolicy == null) throw new IllegalArgumentException("必须指定 rejectionPolicy");
    if (slowTaskMillis < 1 || slowTaskMillis > 86400000)
      throw new IllegalArgumentException("slowTaskMillis 必须在 1 到 86400000 之间");
  }

  public static PoolSettings defaults() {
    return new PoolSettings(2, 4, 32, 60, false, RejectionPolicy.ABORT, 1000);
  }
}
