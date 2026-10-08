package dev.poolpilot.spring;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

public final class TokenAdminAuthenticator implements AdminAuthenticator {
  private record Credential(AdminIdentity identity, byte[] hash, Instant expiry) {}

  private final List<Credential> credentials;
  private final Clock clock;

  public TokenAdminAuthenticator(PoolPilotProperties.Admin settings) {
    this(settings, Clock.systemUTC());
  }

  TokenAdminAuthenticator(PoolPilotProperties.Admin settings, Clock clock) {
    this.clock = clock;
    List<Credential> entries = new ArrayList<>();
    if (settings.getUsers().isEmpty()) {
      String token = settings.getToken();
      if (token == null || !token.matches("[\\x21-\\x7E]{12,256}"))
        throw new IllegalArgumentException(
            "Admin token must contain 12 to 256 visible ASCII characters");
      entries.add(new Credential(new AdminIdentity("token-admin", true), hash(token), null));
    } else {
      if (settings.getToken() != null && !settings.getToken().isBlank())
        throw new IllegalArgumentException("Configure either a shared token or named users");
      if (settings.getUsers().size() > 100)
        throw new IllegalArgumentException("At most 100 admin users");
      Set<String> names = new HashSet<>(), hashes = new HashSet<>();
      for (var user : settings.getUsers()) {
        AdminIdentity identity =
            new AdminIdentity(
                user.getName(), user.getRole() == PoolPilotProperties.Admin.Role.OPERATOR);
        String encoded = user.getTokenSha256();
        if (user.getRole() == null
            || encoded == null
            || !encoded.matches("[0-9a-fA-F]{64}")
            || !names.add(identity.name())
            || !hashes.add(encoded.toLowerCase(Locale.ROOT)))
          throw new IllegalArgumentException(
              "Admin users require unique names and SHA-256 token hashes");
        entries.add(
            new Credential(identity, HexFormat.of().parseHex(encoded), user.getExpiresAt()));
      }
    }
    credentials = List.copyOf(entries);
  }

  static byte[] hash(String token) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  @Override
  public Optional<AdminIdentity> authenticate(HttpServletRequest request) {
    String token = request.getHeader("X-PoolPilot-Token");
    if (token == null || !token.matches("[\\x21-\\x7E]{12,256}")) return Optional.empty();
    byte[] supplied = hash(token);
    AdminIdentity match = null;
    Instant now = clock.instant();
    for (Credential credential : credentials) {
      boolean equal = MessageDigest.isEqual(credential.hash(), supplied);
      if (equal && (credential.expiry() == null || now.isBefore(credential.expiry())))
        match = credential.identity();
    }
    return Optional.ofNullable(match);
  }
}
