package dev.poolpilot.spring;

import dev.poolpilot.core.*;
import java.util.*;

public record PoolConfigDocument(
    int schemaVersion, String application, long revision, List<Update> updates) {
  public record Update(String pool, long expectedVersion, String reason, PoolSettings settings) {
    public Update {
      if (pool == null
          || !pool.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")
          || expectedVersion < 1
          || reason == null
          || reason.isBlank()
          || reason.length() > 200)
        throw new IllegalArgumentException("Invalid external pool update");
      Objects.requireNonNull(settings);
    }
  }

  public PoolConfigDocument {
    if (schemaVersion != 1
        || revision < 1
        || revision == Long.MAX_VALUE
        || application == null
        || !application.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}"))
      throw new IllegalArgumentException("Invalid configuration source document");
    updates = List.copyOf(updates);
    if (updates.isEmpty()
        || updates.size() > 32
        || updates.stream().map(Update::pool).distinct().count() != updates.size())
      throw new IllegalArgumentException("Configuration requires 1 to 32 unique pools");
  }
}
