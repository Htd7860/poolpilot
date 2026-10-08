package dev.poolpilot.core;

/** Registry-owned storage. A failed save must leave the previous document intact. */
public interface PoolStateStore extends AutoCloseable {
  RegistryState load();

  void save(RegistryState state);

  default boolean enabled() {
    return true;
  }

  @Override
  default void close() {}

  static PoolStateStore disabled() {
    return new PoolStateStore() {
      public RegistryState load() {
        return RegistryState.empty();
      }

      public void save(RegistryState state) {}

      public boolean enabled() {
        return false;
      }
    };
  }
}
