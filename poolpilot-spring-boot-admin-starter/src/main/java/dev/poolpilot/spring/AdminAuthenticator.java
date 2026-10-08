package dev.poolpilot.spring;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/**
 * Integrates verified application authentication; never derive identity from an untrusted header.
 */
@FunctionalInterface
public interface AdminAuthenticator {
  Optional<AdminIdentity> authenticate(HttpServletRequest request);
}
