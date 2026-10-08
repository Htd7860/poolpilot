package dev.poolpilot.core;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

public final class ManagedPoolRegistry implements AutoCloseable {
  @FunctionalInterface
  public interface Subscription extends AutoCloseable {
    @Override
    void close();
  }

  private final List<Consumer<ManagedThreadPool>> registrationListeners = new ArrayList<>();
  private final ConcurrentHashMap<String, ManagedThreadPool> pools = new ConcurrentHashMap<>();
  private final BoundedEventLog<PoolChange> changes = new BoundedEventLog<>(200);
  private final AtomicLong changeIds = new AtomicLong();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final long shutdownGraceSeconds;
  private final PoolStateStore store;
  private final RegistryState restored;
  private final Map<String, Map<String, Long>> sourceRevisions = new HashMap<>();

  public ManagedPoolRegistry(long shutdownGraceSeconds) {
    this(shutdownGraceSeconds, PoolStateStore.disabled());
  }

  public ManagedPoolRegistry(long shutdownGraceSeconds, PoolStateStore store) {
    if (shutdownGraceSeconds < 0 || shutdownGraceSeconds > 300)
      throw new IllegalArgumentException("关闭等待必须在 0 到 300 秒之间");
    this.shutdownGraceSeconds = shutdownGraceSeconds;
    this.store = Objects.requireNonNull(store);
    try {
      restored = Objects.requireNonNull(store.load());
      var chronological = new ArrayList<>(restored.changes());
      Collections.reverse(chronological);
      chronological.forEach(changes::add);
      if (!restored.changes().isEmpty()) changeIds.set(restored.changes().get(0).id());
    } catch (RuntimeException ex) {
      store.close();
      throw ex;
    }
  }

  static void validateName(String name) {
    if (name == null || !name.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}"))
      throw new IllegalArgumentException("线程池名称必须为 1 到 64 位字母、数字、点、横线或下划线");
  }

  public synchronized ManagedThreadPool register(
      String name, PoolSettings settings, TaskDecorator decorator) {
    if (closed.get()) throw new IllegalStateException("registry is closed");
    validateName(name);
    if (pools.containsKey(name)) throw new IllegalArgumentException("线程池名称重复：" + name);
    if (pools.size() >= 32) throw new IllegalArgumentException("单实例最多管理 32 个线程池");
    Objects.requireNonNull(settings);
    var saved = restored.pools().get(name);
    ManagedThreadPool pool =
        new ManagedThreadPool(
            name,
            saved == null ? settings : saved.settings(),
            decorator,
            saved == null ? 1 : saved.version());
    pools.put(name, pool);
    sourceRevisions.put(name, new HashMap<>(saved == null ? Map.of() : saved.sourceRevisions()));
    for (var listener : registrationListeners) {
      try {
        listener.accept(pool);
      } catch (RuntimeException ex) {
        System.getLogger(ManagedPoolRegistry.class.getName())
            .log(System.Logger.Level.WARNING, "Pool registration listener failed for " + name, ex);
      }
    }
    return pool;
  }

  /** Replay existing pools and observe later registrations without a registration gap. */
  public synchronized Subscription observePools(Consumer<ManagedThreadPool> listener) {
    Objects.requireNonNull(listener);
    if (closed.get()) throw new IllegalStateException("registry is closed");
    pools().forEach(listener);
    registrationListeners.add(listener);
    return () -> {
      synchronized (ManagedPoolRegistry.this) {
        registrationListeners.remove(listener);
      }
    };
  }

  public ManagedThreadPool get(String name) {
    ManagedThreadPool pool = pools.get(name);
    if (pool == null) throw new NoSuchElementException("找不到线程池：" + name);
    return pool;
  }

  public List<ManagedThreadPool> pools() {
    return pools.values().stream().sorted(Comparator.comparing(ManagedThreadPool::name)).toList();
  }

  public List<PoolSnapshot> snapshots() {
    return pools().stream().map(ManagedThreadPool::snapshot).toList();
  }

  public synchronized PoolSnapshot reconfigure(
      String name, PoolSettings next, long expectedVersion, String reason, String actor) {
    return change(
        name, next, expectedVersion, reason, actor, PoolChange.Kind.UPDATE, null, null, 0);
  }

  public enum ExternalResult {
    APPLIED,
    STALE
  }

  /** Applies a new source revision only against an explicitly matching local pool version. */
  public synchronized ExternalResult applyExternal(
      String source,
      long revision,
      String name,
      PoolSettings next,
      long expectedVersion,
      String reason) {
    validateName(source);
    if (revision < 1 || revision == Long.MAX_VALUE)
      throw new IllegalArgumentException("Invalid source revision");
    get(name);
    var revisions = sourceRevisions.get(name);
    if (revision <= revisions.getOrDefault(source, 0L)) return ExternalResult.STALE;
    if (!revisions.containsKey(source) && revisions.size() >= 8)
      throw new IllegalArgumentException("At most 8 configuration sources per pool");
    change(
        name,
        next,
        expectedVersion,
        reason,
        "config:" + source,
        PoolChange.Kind.EXTERNAL,
        null,
        source,
        revision);
    return ExternalResult.APPLIED;
  }

  public synchronized Map<String, Long> sourceRevisions(String name) {
    get(name);
    return Map.copyOf(sourceRevisions.get(name));
  }

  /** Restore the configuration before a retained change; append a new version and audit entry. */
  public synchronized PoolSnapshot rollback(
      String name, long changeId, long expectedVersion, String reason, String actor) {
    get(name);
    PoolChange source =
        changes().stream()
            .filter(c -> c.id() == changeId && c.pool().equals(name))
            .findFirst()
            .orElseThrow(ChangeNotFoundException::new);
    return change(
        name,
        source.before(),
        expectedVersion,
        reason,
        actor,
        PoolChange.Kind.ROLLBACK,
        changeId,
        null,
        0);
  }

  private PoolSnapshot change(
      String name,
      PoolSettings next,
      long expectedVersion,
      String reason,
      String actor,
      PoolChange.Kind kind,
      Long sourceChangeId,
      String source,
      long revision) {
    if (closed.get()) throw new IllegalStateException("registry is closed");
    if (reason == null || reason.isBlank() || reason.length() > 200)
      throw new IllegalArgumentException("请填写 1 到 200 字的变更原因");
    if (actor == null || actor.isBlank() || actor.length() > 100)
      throw new IllegalArgumentException("actor is required");
    long id = Math.incrementExact(changeIds.get());
    PoolChange[] committed = new PoolChange[1];
    ManagedThreadPool.Update update =
        get(name)
            .reconfigure(
                next,
                expectedVersion,
                before -> {
                  PoolChange event =
                      new PoolChange(
                          id,
                          Instant.now(),
                          name,
                          actor.trim(),
                          reason.trim(),
                          before.version(),
                          before.version() + 1,
                          before.settings(),
                          next,
                          kind,
                          sourceChangeId);
                  Map<String, RegistryState.SavedPool> saved = new TreeMap<>();
                  for (PoolSnapshot snapshot : snapshots())
                    saved.put(
                        snapshot.name(),
                        new RegistryState.SavedPool(
                            snapshot.version(),
                            snapshot.settings(),
                            sourceRevisions.get(snapshot.name())));
                  var candidateRevisions = new HashMap<>(sourceRevisions.get(name));
                  if (source != null) candidateRevisions.put(source, revision);
                  saved.put(
                      name, new RegistryState.SavedPool(event.version(), next, candidateRevisions));
                  List<PoolChange> history = new ArrayList<>();
                  history.add(event);
                  history.addAll(changes());
                  store.save(
                      new RegistryState(
                          1, saved, history.subList(0, Math.min(200, history.size()))));
                  committed[0] = event;
                });
    changeIds.set(id);
    changes.add(committed[0]);
    if (source != null) sourceRevisions.get(name).put(source, revision);
    return update.after();
  }

  public List<PoolChange> changes() {
    return changes.newestFirst();
  }

  public boolean persistenceEnabled() {
    return store.enabled();
  }

  @Override
  public synchronized void close() {
    if (!closed.compareAndSet(false, true)) return;
    registrationListeners.clear();
    pools.values().forEach(ManagedThreadPool::shutdown);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(shutdownGraceSeconds);
    boolean interrupted = false;
    for (ManagedThreadPool pool : pools.values()) {
      try {
        if (!pool.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS))
          pool.shutdownNow();
      } catch (InterruptedException ex) {
        interrupted = true;
        pool.shutdownNow();
      }
    }
    try {
      store.close();
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }
}
