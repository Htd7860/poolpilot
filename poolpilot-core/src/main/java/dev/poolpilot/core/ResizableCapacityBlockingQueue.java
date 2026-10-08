package dev.poolpilot.core;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.*;
import java.util.function.Predicate;

/** One lock protects occupancy and capacity, including resize and producer wakeups. */
public final class ResizableCapacityBlockingQueue<E> extends AbstractQueue<E>
    implements BlockingQueue<E> {
  private final ArrayDeque<E> items = new ArrayDeque<>();
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition notEmpty = lock.newCondition(), notFull = lock.newCondition();
  private int capacity;

  public ResizableCapacityBlockingQueue(int capacity) {
    if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
    this.capacity = capacity;
  }

  public int capacity() {
    lock.lock();
    try {
      return capacity;
    } finally {
      lock.unlock();
    }
  }

  public void setCapacity(int next) {
    if (next < 1) throw new IllegalArgumentException("capacity must be positive");
    lock.lock();
    try {
      capacity = next;
      notFull.signalAll();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public int size() {
    lock.lock();
    try {
      return items.size();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public int remainingCapacity() {
    lock.lock();
    try {
      return Math.max(0, capacity - items.size());
    } finally {
      lock.unlock();
    }
  }

  private void enqueue(E e) {
    items.addLast(e);
    notEmpty.signal();
  }

  private E dequeue() {
    E e = items.removeFirst();
    notFull.signal();
    return e;
  }

  @Override
  public boolean offer(E e) {
    Objects.requireNonNull(e);
    lock.lock();
    try {
      if (items.size() >= capacity) return false;
      enqueue(e);
      return true;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void put(E e) throws InterruptedException {
    Objects.requireNonNull(e);
    lock.lockInterruptibly();
    try {
      while (items.size() >= capacity) notFull.await();
      enqueue(e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
    Objects.requireNonNull(e);
    long nanos = unit.toNanos(timeout);
    lock.lockInterruptibly();
    try {
      while (items.size() >= capacity) {
        if (nanos <= 0) return false;
        nanos = notFull.awaitNanos(nanos);
      }
      enqueue(e);
      return true;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public E poll() {
    lock.lock();
    try {
      return items.isEmpty() ? null : dequeue();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public E take() throws InterruptedException {
    lock.lockInterruptibly();
    try {
      while (items.isEmpty()) notEmpty.await();
      return dequeue();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public E poll(long timeout, TimeUnit unit) throws InterruptedException {
    long nanos = unit.toNanos(timeout);
    lock.lockInterruptibly();
    try {
      while (items.isEmpty()) {
        if (nanos <= 0) return null;
        nanos = notEmpty.awaitNanos(nanos);
      }
      return dequeue();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public E peek() {
    lock.lock();
    try {
      return items.peekFirst();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean remove(Object item) {
    lock.lock();
    try {
      boolean removed = items.removeFirstOccurrence(item);
      if (removed) notFull.signal();
      return removed;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean contains(Object item) {
    lock.lock();
    try {
      return items.contains(item);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void clear() {
    lock.lock();
    try {
      items.clear();
      notFull.signalAll();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public int drainTo(Collection<? super E> destination) {
    return drainTo(destination, Integer.MAX_VALUE);
  }

  @Override
  public int drainTo(Collection<? super E> destination, int maxElements) {
    Objects.requireNonNull(destination);
    if (destination == this) throw new IllegalArgumentException("Cannot drain to self");
    if (maxElements <= 0) return 0;
    int count = 0;
    lock.lock();
    try {
      while (count < maxElements && !items.isEmpty()) {
        destination.add(items.peekFirst());
        items.removeFirst();
        count++;
      }
      return count;
    } finally {
      if (count > 0) notFull.signalAll();
      lock.unlock();
    }
  }

  @Override
  public Iterator<E> iterator() {
    List<E> copy;
    lock.lock();
    try {
      copy = new ArrayList<>(items);
    } finally {
      lock.unlock();
    }
    Iterator<E> iterator = copy.iterator();
    return new Iterator<>() {
      private E last;
      private boolean canRemove;

      public boolean hasNext() {
        return iterator.hasNext();
      }

      public E next() {
        last = iterator.next();
        canRemove = true;
        return last;
      }

      public void remove() {
        if (!canRemove) throw new IllegalStateException();
        ResizableCapacityBlockingQueue.this.remove(last);
        canRemove = false;
      }
    };
  }

  /** Removes matching elements in one pass; predicates must not modify this queue. */
  List<E> extractMatching(Predicate<? super E> predicate) {
    Objects.requireNonNull(predicate);
    lock.lock();
    List<E> removed = new ArrayList<>();
    try {
      items.removeIf(
          item -> {
            if (!predicate.test(item)) return false;
            removed.add(item);
            return true;
          });
      return removed;
    } finally {
      if (!removed.isEmpty()) notFull.signalAll();
      lock.unlock();
    }
  }
}
