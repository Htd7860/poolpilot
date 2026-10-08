package dev.poolpilot.core;

public final class ChangeNotFoundException extends RuntimeException {
  public ChangeNotFoundException() {
    super("Change not found for this pool in retained history");
  }
}
