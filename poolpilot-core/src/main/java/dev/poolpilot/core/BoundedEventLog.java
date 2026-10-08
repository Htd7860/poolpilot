package dev.poolpilot.core;

import java.util.*;

public final class BoundedEventLog<E> {
  private final int capacity;
  private final ArrayDeque<E> events = new ArrayDeque<>();

  public BoundedEventLog(int capacity) {
    if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
    this.capacity = capacity;
  }

  public synchronized void add(E event) {
    Objects.requireNonNull(event);
    if (events.size() == capacity) events.removeFirst();
    events.addLast(event);
  }

  public synchronized List<E> newestFirst() {
    List<E> result = new ArrayList<>(events);
    Collections.reverse(result);
    return List.copyOf(result);
  }
}
