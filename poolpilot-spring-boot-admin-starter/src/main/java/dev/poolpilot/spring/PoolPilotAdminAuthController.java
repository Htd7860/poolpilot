package dev.poolpilot.spring;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class PoolPilotAdminAuthController {
  public record AuthenticationInfo(PoolPilotProperties.Admin.Authentication mode) {}

  private final PoolPilotProperties.Admin.Authentication mode;

  public PoolPilotAdminAuthController(PoolPilotProperties properties) {
    mode = properties.getAdmin().getAuthentication();
  }

  @GetMapping("/poolpilot/api/auth")
  public ResponseEntity<AuthenticationInfo> authentication() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(new AuthenticationInfo(mode));
  }
}
