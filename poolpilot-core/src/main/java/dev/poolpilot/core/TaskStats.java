package dev.poolpilot.core;

public record TaskStats(
    long submitted,
    long started,
    long completed,
    long failed,
    long cancelled,
    long rejected,
    long callerRuns,
    long slowTasks,
    long observerFailures,
    long running,
    double averageWaitMillis,
    double averageExecutionMillis,
    double waitP95Millis,
    double executionP95Millis,
    double executionP99Millis,
    int windowSamples,
    long queueTimeouts,
    long runTimeouts,
    long trackingDropped) {}
