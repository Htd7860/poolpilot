package dev.poolpilot.core;

import java.time.Instant;
import java.util.Objects;

public record PoolChange(
    long id,
    Instant timestamp,
    String pool,
    String actor,
    String reason,
    long previousVersion,
    long version,
    PoolSettings before,
    PoolSettings after,
    Kind kind,
    Long sourceChangeId) {
  public enum Kind {
    UPDATE,
    ROLLBACK,
    EXTERNAL
  }

  public PoolChange {
    ManagedPoolRegistry.validateName(pool);
    Objects.requireNonNull(timestamp);
    Objects.requireNonNull(before);
    Objects.requireNonNull(after);
    Objects.requireNonNull(kind);
    if (id < 1
        || id == Long.MAX_VALUE
        || previousVersion < 1
        || version != previousVersion + 1
        || version == Long.MAX_VALUE)
      throw new IllegalArgumentException("Invalid change version or id");
    if (actor == null
        || actor.isBlank()
        || actor.length() > 100
        || reason == null
        || reason.isBlank()
        || reason.length() > 200)
      throw new IllegalArgumentException("Invalid change actor or reason");
    if (kind != Kind.ROLLBACK && sourceChangeId != null
        || kind == Kind.ROLLBACK
            && (sourceChangeId == null || sourceChangeId < 1 || sourceChangeId >= id))
      throw new IllegalArgumentException("Invalid rollback source");
  }
}
