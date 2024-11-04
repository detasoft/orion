package pro.deta.orion.comm.v3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import pro.deta.orion.comm.util.RecentValueBuffer;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

public class RecentValueBufferTest {
    @Test
    public void testIndexCircle() {
        int size = 10;
        RecentValueBuffer<String> c = new RecentValueBuffer<>(size, "");
        for (int i = 1; i < 1000; i++) {
            int current = c.current();
            assertThat(current).isEqualTo(i % size);
            c.next(current, c.nextIndex(current));
        }
    }

    @Test
    public void testIndexRecent() {
        int size = 10;
        RecentValueBuffer<String> c = new RecentValueBuffer<>(size, "");
        assertThat(c.getRecent()).isEqualTo("");
        for (int i = 1; i < 100000; i++) {
            String s = "" + i;
            c.put(s);
            assertThat(c.getRecent()).isEqualTo(s);
        }
    }

    @Test
    public void testPutGetRecentAfterWrapSingleThread() {
        int size = 5;
        RecentValueBuffer<String> c = new RecentValueBuffer<>(size, "");
        for (int i = 0; i < size * 3; i++) {
            String v = "v" + i;
            c.put(v);
            assertThat(c.getRecent()).isEqualTo(v);
        }
        assertThat(c.getRecent()).isEqualTo("v" + (size * 3 - 1));
    }

    @Test
    @Timeout(10)
    public void testNextCASSingleWinnerUnderContention() throws InterruptedException {
        RecentValueBuffer<String> c = new RecentValueBuffer<>(8, "");
        int prev = c.current();
        int next = c.nextIndex(prev);
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        if (c.next(prev, next)) {
                            success.incrementAndGet();
                        }
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            done.await(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(success.get()).isEqualTo(1);
        assertThat(c.current()).isEqualTo(next);
    }

    @Test
    @Timeout(15)
    public void testConcurrentProducersRecentValueBelongsToExpectedSet() throws Exception {
        int producers = 6;
        int perProducer = 2000;
        int size = 128;
        RecentValueBuffer<String> c = new RecentValueBuffer<>(size, "P1-1");

        Set<String> expected = Collections.synchronizedSet(new HashSet<>());
        for (int p = 0; p < producers; p++) {
            for (int i = 0; i < perProducer; i++) {
                expected.add("P" + p + "-" + i);
            }
        }

        ExecutorService pool = Executors.newFixedThreadPool(producers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(producers);

        for (int p = 0; p < producers; p++) {
            final int id = p;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perProducer; i++) {
                        c.put("P" + id + "-" + i);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();

        // While producers are working, poll getRecent and collect seen values.
        Set<String> seen = Collections.synchronizedSet(new HashSet<>());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (done.getCount() > 0 && System.nanoTime() < deadline) {
            String v = c.getRecent();
            if (v != null) {
                // Validate no unexpected values
                assertThat(expected).contains(v);
                seen.add(v);
            }
            // Yield to reduce CPU}
            Thread.yield();
        }

        // Ensure all producers finished
        boolean finished = done.await(5, TimeUnit.SECONDS);
        assertThat(finished).isTrue();

        // After completion, getRecent should be non-null and from expected set
        String last = c.getRecent();
        assertThat(last).isNotNull();
        assertThat(expected).contains(last);
        assertThat(seen.isEmpty()).isFalse();

        pool.shutdownNow();
    }

    @Test
    @Timeout(15000)
    public void testStressConcurrentPutAndGetRecentNoUnexpectedValues() throws Exception {
        int producers = 4;
        int perProducer = 300000;
        int size = 64;
        RecentValueBuffer<String> c = new RecentValueBuffer<>(size, "");

        Set<String> expected = Collections.synchronizedSet(new HashSet<>());
        expected.add("");
        for (int p = 0; p < producers; p++) {
            for (int i = 0; i < perProducer; i++) {
                expected.add("X" + p + ":" + i);
            }
        }

        ExecutorService pool = Executors.newFixedThreadPool(producers + 1);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(producers);

        for (int p = 0; p < producers; p++) {
            final int id = p;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perProducer; i++) {
                        c.put("X" + id + ":" + i);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        // Reader task to continuously validate observed values belong to expected set
        AtomicInteger checks = new AtomicInteger();
        AtomicInteger nullsSeen = new AtomicInteger();
        Future<?> reader = pool.submit(() -> {
            try {
                start.await();
                while (done.getCount() > 0) {
                    try {
                        String v = c.getRecent();
                        if (v == null) {
                            nullsSeen.incrementAndGet();
                        } else {
                            assertThat(expected).contains(v);
                        }
                        checks.incrementAndGet();
                    } catch (Throwable e) {
                        fail("Caught Exception: " + e.getMessage());
                    }
                    // slight pause to avoid busy loop
                    Thread.yield();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        start.countDown();

        boolean finished = done.await(10, TimeUnit.HOURS);
        assertThat(finished).isTrue();
        // Stop reader
        reader.cancel(true);
        pool.shutdownNow();

        // After writes completed, getRecent should be non-null and belong to expected
        String last = c.getRecent();
        assertThat(last).isNotNull();
        assertThat(expected).contains(last);
        // Ensure the reader actually performed some checks
        assertThat(checks.get()).isGreaterThan(0);
        System.out.println("Nulls seen: " + nullsSeen.get() + ", checks: " + checks.get());
    }
}