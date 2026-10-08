package dev.poolpilot.spring;

import dev.poolpilot.core.*;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("poolpilot")
public class PoolPilotProperties {
  private boolean enabled = true;
  private long shutdownGraceSeconds = 5;
  private List<Pool> pools = new ArrayList<>();
  private Admin admin = new Admin();
  private Alerts alerts = new Alerts();
  private Persistence persistence = new Persistence();
  private Timeouts timeouts = new Timeouts();
  private ConfigSources configSources = new ConfigSources();

  public ConfigSources getConfigSources() {
    return configSources;
  }

  public void setConfigSources(ConfigSources value) {
    configSources = value;
  }

  public static class ConfigSources {
    private String application = "application",
        instance = "instance-" + UUID.randomUUID().toString().substring(0, 8);
    private long intervalMillis = 5000;
    private List<HttpSource> http = new ArrayList<>();

    public String getApplication() {
      return application;
    }

    public void setApplication(String value) {
      application = value;
    }

    public String getInstance() {
      return instance;
    }

    public void setInstance(String value) {
      instance = value;
    }

    public long getIntervalMillis() {
      return intervalMillis;
    }

    public void setIntervalMillis(long value) {
      intervalMillis = value;
    }

    public List<HttpSource> getHttp() {
      return http;
    }

    public void setHttp(List<HttpSource> value) {
      http = value;
    }
  }

  public static class HttpSource {
    private String id, url, bearerToken, accessToken;
    private HttpPoolConfigSource.Format format = HttpPoolConfigSource.Format.DOCUMENT;

    public HttpPoolConfigSource.Format getFormat() {
      return format;
    }

    public void setFormat(HttpPoolConfigSource.Format value) {
      format = value;
    }

    public String getAccessToken() {
      return accessToken;
    }

    public void setAccessToken(String value) {
      accessToken = value;
    }

    private long timeoutMillis = 3000;

    public String getId() {
      return id;
    }

    public void setId(String value) {
      id = value;
    }

    public String getUrl() {
      return url;
    }

    public void setUrl(String value) {
      url = value;
    }

    public String getBearerToken() {
      return bearerToken;
    }

    public void setBearerToken(String value) {
      bearerToken = value;
    }

    public long getTimeoutMillis() {
      return timeoutMillis;
    }

    public void setTimeoutMillis(long value) {
      timeoutMillis = value;
    }
  }

  public Timeouts getTimeouts() {
    return timeouts;
  }

  public void setTimeouts(Timeouts value) {
    timeouts = value;
  }

  public static class Timeouts {
    private boolean enabled = true;
    private long intervalMillis = 250, queueMillis = 10000, runMillis = 30000;
    private int maxTrackedTasks = 10000;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean value) {
      enabled = value;
    }

    public long getIntervalMillis() {
      return intervalMillis;
    }

    public void setIntervalMillis(long value) {
      intervalMillis = value;
    }

    public long getQueueMillis() {
      return queueMillis;
    }

    public void setQueueMillis(long value) {
      queueMillis = value;
    }

    public long getRunMillis() {
      return runMillis;
    }

    public void setRunMillis(long value) {
      runMillis = value;
    }

    public int getMaxTrackedTasks() {
      return maxTrackedTasks;
    }

    public void setMaxTrackedTasks(int value) {
      maxTrackedTasks = value;
    }

    public TaskTimeoutPolicy policy() {
      if (intervalMillis < 25 || intervalMillis > 60000)
        throw new IllegalArgumentException("Timeout scan interval must be 25 to 60000 ms");
      return enabled
          ? new TaskTimeoutPolicy(queueMillis, runMillis, maxTrackedTasks)
          : TaskTimeoutPolicy.disabled();
    }
  }

  public Persistence getPersistence() {
    return persistence;
  }

  public void setPersistence(Persistence value) {
    persistence = value;
  }

  public static class Persistence {
    private boolean enabled;
    private String path = "./data/poolpilot-state.json";

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean value) {
      enabled = value;
    }

    public String getPath() {
      return path;
    }

    public void setPath(String value) {
      path = value;
    }
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public long getShutdownGraceSeconds() {
    return shutdownGraceSeconds;
  }

  public void setShutdownGraceSeconds(long value) {
    shutdownGraceSeconds = value;
  }

  public List<Pool> getPools() {
    return pools;
  }

  public void setPools(List<Pool> value) {
    pools = value;
  }

  public Admin getAdmin() {
    return admin;
  }

  public void setAdmin(Admin value) {
    admin = value;
  }

  public Alerts getAlerts() {
    return alerts;
  }

  public void setAlerts(Alerts value) {
    alerts = value;
  }

  public static class Pool {
    private String name;
    private int coreSize = 2, maxSize = 4, queueCapacity = 32;
    private long keepAliveSeconds = 60, slowTaskMillis = 1000;
    private boolean allowCoreTimeout;
    private RejectionPolicy rejectionPolicy = RejectionPolicy.ABORT;

    public String getName() {
      return name;
    }

    public void setName(String value) {
      name = value;
    }

    public int getCoreSize() {
      return coreSize;
    }

    public void setCoreSize(int value) {
      coreSize = value;
    }

    public int getMaxSize() {
      return maxSize;
    }

    public void setMaxSize(int value) {
      maxSize = value;
    }

    public int getQueueCapacity() {
      return queueCapacity;
    }

    public void setQueueCapacity(int value) {
      queueCapacity = value;
    }

    public long getKeepAliveSeconds() {
      return keepAliveSeconds;
    }

    public void setKeepAliveSeconds(long value) {
      keepAliveSeconds = value;
    }

    public long getSlowTaskMillis() {
      return slowTaskMillis;
    }

    public void setSlowTaskMillis(long value) {
      slowTaskMillis = value;
    }

    public boolean isAllowCoreTimeout() {
      return allowCoreTimeout;
    }

    public void setAllowCoreTimeout(boolean value) {
      allowCoreTimeout = value;
    }

    public RejectionPolicy getRejectionPolicy() {
      return rejectionPolicy;
    }

    public void setRejectionPolicy(RejectionPolicy value) {
      rejectionPolicy = value;
    }

    public PoolSettings settings() {
      return new PoolSettings(
          coreSize,
          maxSize,
          queueCapacity,
          keepAliveSeconds,
          allowCoreTimeout,
          rejectionPolicy,
          slowTaskMillis);
    }
  }

  public static class Admin {
    public enum Authentication {
      TOKEN,
      APPLICATION
    }

    public enum Role {
      READER,
      OPERATOR
    }

    private Authentication authentication = Authentication.TOKEN;
    private List<User> users = new ArrayList<>();

    public Authentication getAuthentication() {
      return authentication;
    }

    public void setAuthentication(Authentication value) {
      authentication = value;
    }

    public List<User> getUsers() {
      return users;
    }

    public void setUsers(List<User> value) {
      users = value;
    }

    public static class User {
      private String name, tokenSha256;
      private Role role = Role.READER;
      private java.time.Instant expiresAt;

      public String getName() {
        return name;
      }

      public void setName(String value) {
        name = value;
      }

      public String getTokenSha256() {
        return tokenSha256;
      }

      public void setTokenSha256(String value) {
        tokenSha256 = value;
      }

      public Role getRole() {
        return role;
      }

      public void setRole(Role value) {
        role = value;
      }

      public java.time.Instant getExpiresAt() {
        return expiresAt;
      }

      public void setExpiresAt(java.time.Instant value) {
        expiresAt = value;
      }
    }

    private boolean enabled;
    private String token = "";

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean value) {
      enabled = value;
    }

    public String getToken() {
      return token;
    }

    public void setToken(String value) {
      token = value;
    }
  }

  public static class Alerts {
    private boolean enabled = true;
    private long intervalMillis = 1000, cooldownSeconds = 15;
    private int activityPercent = 90, queuePercent = 80;
    private int notificationThreads = 1, notificationQueueCapacity = 128;
    private long notificationShutdownMillis = 1000;

    public int getNotificationThreads() {
      return notificationThreads;
    }

    public void setNotificationThreads(int value) {
      notificationThreads = value;
    }

    public int getNotificationQueueCapacity() {
      return notificationQueueCapacity;
    }

    public void setNotificationQueueCapacity(int value) {
      notificationQueueCapacity = value;
    }

    public long getNotificationShutdownMillis() {
      return notificationShutdownMillis;
    }

    public void setNotificationShutdownMillis(long value) {
      notificationShutdownMillis = value;
    }

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean value) {
      enabled = value;
    }

    public long getIntervalMillis() {
      return intervalMillis;
    }

    public void setIntervalMillis(long value) {
      intervalMillis = value;
    }

    public long getCooldownSeconds() {
      return cooldownSeconds;
    }

    public void setCooldownSeconds(long value) {
      cooldownSeconds = value;
    }

    public int getActivityPercent() {
      return activityPercent;
    }

    public void setActivityPercent(int value) {
      activityPercent = value;
    }

    public int getQueuePercent() {
      return queuePercent;
    }

    public void setQueuePercent(int value) {
      queuePercent = value;
    }

    public void validate() {
      if (notificationThreads < 1
          || notificationThreads > 8
          || notificationQueueCapacity < 1
          || notificationQueueCapacity > 10000
          || notificationShutdownMillis < 0
          || notificationShutdownMillis > 30000)
        throw new IllegalArgumentException("Invalid notification worker, queue or shutdown limit");
      if (intervalMillis < 250 || intervalMillis > 60000)
        throw new IllegalArgumentException("告警检查间隔必须在 250 到 60000 毫秒之间");
      if (cooldownSeconds < 1 || cooldownSeconds > 3600)
        throw new IllegalArgumentException("告警冷却必须在 1 到 3600 秒之间");
      if (activityPercent < 1 || activityPercent > 100 || queuePercent < 1 || queuePercent > 100)
        throw new IllegalArgumentException("告警百分比必须在 1 到 100 之间");
    }
  }
}
