package dev.poolpilot.spring;

/** Implementations must honor interruption and perform bounded I/O. */
public interface PoolConfigSource extends AutoCloseable {
  String id();

  PoolConfigDocument fetch() throws Exception;

  @Override
  default void close() {}
}
