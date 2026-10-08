package dev.poolpilot.core;

@FunctionalInterface
public interface TaskObserver {
  void completed(String poolName, TaskSample sample);
}
