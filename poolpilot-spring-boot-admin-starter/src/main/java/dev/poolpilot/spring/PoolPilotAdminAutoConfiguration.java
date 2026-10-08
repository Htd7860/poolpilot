package dev.poolpilot.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.poolpilot.core.ManagedPoolRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Locale;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.filter.OncePerRequestFilter;

@AutoConfiguration(after = PoolPilotAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(ManagedPoolRegistry.class)
@ConditionalOnProperty(prefix = "poolpilot.admin", name = "enabled", havingValue = "true")
public class PoolPilotAdminAutoConfiguration {
  @Bean
  public PoolPilotAdminAuthController poolPilotAdminAuthController(PoolPilotProperties properties) {
    return new PoolPilotAdminAuthController(properties);
  }

  @Bean
  public PoolPilotAdminController poolPilotAdminController(
      ManagedPoolRegistry registry, PoolAlertMonitor monitor, PoolConfigRefresher refresher) {
    return new PoolPilotAdminController(registry, monitor, refresher);
  }

  @Bean
  @ConditionalOnMissingBean(AdminAuthenticator.class)
  public AdminAuthenticator poolPilotAdminAuthenticator(PoolPilotProperties properties) {
    if (properties.getAdmin().getAuthentication()
        == PoolPilotProperties.Admin.Authentication.APPLICATION)
      return request -> {
        var principal = request.getUserPrincipal();
        if (principal == null
            || !(request.isUserInRole("POOLPILOT_READ") || request.isUserInRole("POOLPILOT_WRITE")))
          return java.util.Optional.empty();
        return java.util.Optional.of(
            new AdminIdentity(principal.getName(), request.isUserInRole("POOLPILOT_WRITE")));
      };
    return new TokenAdminAuthenticator(properties.getAdmin());
  }

  @Bean
  public FilterRegistrationBean<OncePerRequestFilter> poolPilotAdminAuthentication(
      AdminAuthenticator authenticator, ObjectMapper mapper) {
    OncePerRequestFilter filter =
        new OncePerRequestFilter() {
          @Override
          protected boolean shouldNotFilter(HttpServletRequest request) {
            boolean read = "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
            return read
                && (request.getContextPath() + "/poolpilot/api/auth")
                    .equals(request.getRequestURI());
          }

          @Override
          protected void doFilterInternal(
              HttpServletRequest request, HttpServletResponse response, FilterChain chain)
              throws ServletException, IOException {
            var identity = authenticator.authenticate(request);
            response.setHeader("Cache-Control", "no-store");
            boolean read = "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
            if (identity.isEmpty() || !read && !identity.get().writable()) {
              response.setStatus(identity.isEmpty() ? 401 : 403);
              response.setContentType("application/json");
              response.setCharacterEncoding("UTF-8");
              String language = request.getHeader("Accept-Language");
              Locale locale = Locale.ENGLISH;
              if (language != null) {
                try {
                  var ranges = Locale.LanguageRange.parse(language);
                  locale =
                      Locale.lookup(
                          ranges, java.util.List.of(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH));
                  if (locale == null) locale = Locale.ENGLISH;
                } catch (IllegalArgumentException ignored) {
                  /* Invalid headers use English. */
                }
              }
              mapper.writeValue(
                  response.getWriter(),
                  ConsoleMessages.error(identity.isEmpty() ? "UNAUTHORIZED" : "FORBIDDEN", locale));
              return;
            }
            request.setAttribute(AdminIdentity.ATTRIBUTE, identity.get());
            try {
              chain.doFilter(request, response);
            } finally {
              request.removeAttribute(AdminIdentity.ATTRIBUTE);
            }
          }
        };
    FilterRegistrationBean<OncePerRequestFilter> registration =
        new FilterRegistrationBean<>(filter);
    registration.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER + 10);
    registration.addUrlPatterns("/poolpilot/api/*");
    return registration;
  }
}
