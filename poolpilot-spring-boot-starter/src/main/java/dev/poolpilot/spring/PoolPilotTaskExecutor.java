package dev.poolpilot.spring;

import dev.poolpilot.core.*;
import java.util.Objects;
import java.util.concurrent.*;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.core.task.support.TaskExecutorAdapter;

/** Spring task executor backed by a registry-owned pool. */
public final class PoolPilotTaskExecutor implements AsyncTaskExecutor {
  private final ManagedThreadPool pool;
  private final TaskExecutorAdapter adapter;

  public PoolPilotTaskExecutor(ManagedPoolRegistry registry, String poolName) {
    pool = Objects.requireNonNull(registry).get(poolName);
    adapter = new TaskExecutorAdapter(pool);
  }

  @Override
  public void execute(Runnable task) {
    adapter.execute(task);
  }

  @Override
  public Future<?> submit(Runnable task) {
    return adapter.submit(task);
  }

  @Override
  public <T> Future<T> submit(Callable<T> task) {
    return adapter.submit(task);
  }

  @Override
  public <T> CompletableFuture<T> submitCompletable(Callable<T> task) {
    try {
      return pool.submitStage(task);
    } catch (RejectedExecutionException ex) {
      throw new TaskRejectedException("Pool " + pool.name() + " rejected task", ex);
    }
  }

  @Override
  public CompletableFuture<Void> submitCompletable(Runnable task) {
    return submitCompletable(Executors.callable(task, null));
  }
}
