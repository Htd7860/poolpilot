package dev.poolpilot.spring;

public record AdminIdentity(String name, boolean writable) {
  public static final String ATTRIBUTE = "dev.poolpilot.spring.AdminIdentity";

  public AdminIdentity {
    if (name == null || !name.matches("[a-zA-Z0-9][a-zA-Z0-9@._:-]{0,99}"))
      throw new IllegalArgumentException("Invalid admin identity name");
  }
}
