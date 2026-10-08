package dev.poolpilot.core;

public record PoolSnapshot(
    String name,
    long version,
    PoolSettings settings,
    int poolSize,
    int activeCount,
    int largestPoolSize,
    int queueSize,
    int queueRemainingCapacity,
    boolean shutdown,
    TaskStats tasks) {}
