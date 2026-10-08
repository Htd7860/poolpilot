package dev.poolpilot.core;

@FunctionalInterface
public interface TaskDecorator {
  Runnable decorate(Runnable task);

  static TaskDecorator identity() {
    return task -> task;
  }

  /** Composes decorators with the first decorator surrounding all later decorators. */
  static TaskDecorator compose(java.util.List<TaskDecorator> decorators) {
    var chain = java.util.List.copyOf(decorators);
    return task -> {
      Runnable decorated = task;
      for (int i = chain.size() - 1; i >= 0; i--)
        decorated = java.util.Objects.requireNonNull(chain.get(i).decorate(decorated));
      return decorated;
    };
  }
}
