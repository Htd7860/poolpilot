package dev.poolpilot.core;

public final class VersionConflictException extends RuntimeException {
  private final long actualVersion;

  public VersionConflictException(long actual) {
    super("Configuration has changed; reload before retrying. Current version: " + actual);
    this.actualVersion = actual;
  }

  public long actualVersion() {
    return actualVersion;
  }
}
