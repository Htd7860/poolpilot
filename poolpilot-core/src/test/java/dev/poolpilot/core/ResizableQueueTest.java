package dev.poolpilot.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(5)
class ResizableQueueTest {
  @Test
  void shrinkPreservesContentsAndBackpressuresUntilBelowCapacity() {
    var q = new ResizableCapacityBlockingQueue<Integer>(4);
    for (int i = 0; i < 4; i++) assertTrue(q.offer(i));
    q.setCapacity(1);
    assertEquals(4, q.size());
    assertEquals(0, q.remainingCapacity());
    assertFalse(q.offer(9));
    assertEquals(List.of(0, 1, 2), List.of(q.poll(), q.poll(), q.poll()));
    assertFalse(q.offer(9));
    assertEquals(3, q.poll());
    assertTrue(q.offer(9));
  }

  @Test
  void growingWakesBlockedProducer() throws Exception {
    var q = new ResizableCapacityBlockingQueue<Integer>(1);
    q.put(1);
    var executor = Executors.newSingleThreadExecutor();
    try {
      var entered = new CountDownLatch(1);
      Future<?> producer =
          executor.submit(
              () -> {
                entered.countDown();
                q.put(2);
                return null;
              });
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      q.setCapacity(2);
      producer.get(1, TimeUnit.SECONDS);
      assertEquals(1, q.take());
      assertEquals(2, q.take());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void interruptedWaiterCannotInsert() throws Exception {
    var q = new ResizableCapacityBlockingQueue<Integer>(1);
    q.put(1);
    var executor = Executors.newSingleThreadExecutor();
    try {
      Future<?> producer =
          executor.submit(
              () -> {
                q.put(2);
                return null;
              });
      assertTrue(producer.cancel(true));
      assertEquals(1, q.size());
      assertEquals(1, q.take());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void timedOperationsAndNullValidation() throws Exception {
    var q = new ResizableCapacityBlockingQueue<Integer>(1);
    assertNull(q.poll(10, TimeUnit.MILLISECONDS));
    q.put(1);
    assertFalse(q.offer(2, 10, TimeUnit.MILLISECONDS));
    assertThrows(NullPointerException.class, () -> q.offer(null));
    assertThrows(IllegalArgumentException.class, () -> q.setCapacity(0));
    assertThrows(IllegalArgumentException.class, () -> q.drainTo(q));
  }

  @Test
  void failedDrainRetainsUntransferredElement() {
    var q = new ResizableCapacityBlockingQueue<Integer>(3);
    q.addAll(List.of(1, 2, 3));
    var sink =
        new ArrayList<Integer>() {
          @Override
          public boolean add(Integer n) {
            if (n == 2) throw new IllegalStateException("sink failed");
            return super.add(n);
          }
        };
    assertThrows(IllegalStateException.class, () -> q.drainTo(sink));
    assertEquals(List.of(1), sink);
    assertEquals(List.of(2, 3), new ArrayList<>(q));
    assertEquals(1, q.remainingCapacity());
  }

  @Test
  void snapshotIteratorCanRemoveFromLiveQueueAndClearRestoresCapacity() {
    var q = new ResizableCapacityBlockingQueue<Integer>(3);
    q.addAll(List.of(1, 2));
    var iterator = q.iterator();
    assertEquals(1, iterator.next());
    iterator.remove();
    assertEquals(List.of(2), new ArrayList<>(q));
    assertThrows(IllegalStateException.class, iterator::remove);
    q.clear();
    assertEquals(3, q.remainingCapacity());
  }
}
