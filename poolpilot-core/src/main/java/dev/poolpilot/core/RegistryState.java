package dev.poolpilot.core;

import java.util.*;

/** Persisted pool settings, versions and the newest 200 configuration changes. */
public record RegistryState(
    int schemaVersion, Map<String, SavedPool> pools, List<PoolChange> changes) {
  public record SavedPool(long version, PoolSettings settings, Map<String, Long> sourceRevisions) {
    public SavedPool(long version, PoolSettings settings) {
      this(version, settings, Map.of());
    }

    public SavedPool {
      if (version < 1 || version == Long.MAX_VALUE)
        throw new IllegalArgumentException("Invalid saved pool version");
      Objects.requireNonNull(settings);
      sourceRevisions = Map.copyOf(sourceRevisions);
      if (sourceRevisions.size() > 8)
        throw new IllegalArgumentException("At most 8 configuration sources per pool");
      sourceRevisions.forEach(
          (source, revision) -> {
            ManagedPoolRegistry.validateName(source);
            if (revision < 1 || revision == Long.MAX_VALUE)
              throw new IllegalArgumentException("Invalid source revision");
          });
    }
  }

  public RegistryState {
    if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported state schema version");
    pools = Map.copyOf(pools);
    changes = List.copyOf(changes);
    if (pools.size() > 32 || changes.size() > 200)
      throw new IllegalArgumentException("State exceeds registry limits");
    pools.keySet().forEach(ManagedPoolRegistry::validateName);
    long previousId = Long.MAX_VALUE;
    Set<String> latest = new HashSet<>();
    for (PoolChange change : changes) {
      if (change.id() >= previousId)
        throw new IllegalArgumentException("Changes must have decreasing unique ids");
      previousId = change.id();
      SavedPool saved = pools.get(change.pool());
      if (latest.add(change.pool())
          && saved != null
          && (saved.version() != change.version() || !saved.settings().equals(change.after())))
        throw new IllegalArgumentException("Latest change disagrees with saved configuration");
    }
  }

  public static RegistryState empty() {
    return new RegistryState(1, Map.of(), List.of());
  }
}
