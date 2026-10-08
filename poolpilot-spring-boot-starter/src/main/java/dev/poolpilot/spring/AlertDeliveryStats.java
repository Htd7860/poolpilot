package dev.poolpilot.spring;

public record AlertDeliveryStats(
    long accepted,
    long completed,
    long failed,
    long dropped,
    int queued,
    int active,
    boolean closed) {}
