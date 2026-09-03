package pro.deta.orion.agent.server.journal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.protocol.EventId;
import pro.deta.orion.agent.protocol.SessionEventRecord;
import pro.deta.orion.agent.protocol.SessionId;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static pro.deta.orion.agent.server.journal.JournalTestRecords.event;

class JournalConcurrencyTest {
    private static final SessionId SESSION_A = new SessionId("session-a");
    private static final SessionId SESSION_B = new SessionId("session-b");

    @TempDir
    Path root;

    @Test
    void aBlockedSessionDoesNotDelayAnotherSession() throws Exception {
        BlockingAppendOperations operations = new BlockingAppendOperations("session-a-append");
        try (var storage = storage(operations);
                ExecutorService sessionA = namedExecutor("session-a-append");
                ExecutorService sessionB = namedExecutor("session-b-append")) {
            Future<JournalAppendResult> blocked = null;
            Future<JournalAppendResult> independent = null;
            try {
                blocked = sessionA.submit(
                        () -> storage.append(SESSION_A, List.of(event(1))));
                assertThat(operations.blockedForce.await(10, TimeUnit.SECONDS)).isTrue();

                independent = sessionB.submit(
                        () -> storage.append(SESSION_B, List.of(event(10))));

                assertThat(independent.get(5, TimeUnit.SECONDS).durableThrough())
                        .contains(new EventId(10));
                assertThat(blocked.isDone()).isFalse();
                operations.releaseForce.countDown();
                assertThat(blocked.get(5, TimeUnit.SECONDS).durableThrough())
                        .contains(new EventId(1));
            } finally {
                operations.releaseForce.countDown();
                cancel(blocked);
                cancel(independent);
            }
        }
    }

    @Test
    void appendsToOneSessionSerializeWritesAndCatalogPublication() throws Exception {
        BlockingAppendOperations operations = new BlockingAppendOperations("first-append");
        try (var storage = storage(operations);
                ExecutorService firstExecutor = namedExecutor("first-append");
                ExecutorService secondExecutor = namedExecutor("second-append")) {
            Future<JournalAppendResult> first = null;
            Future<JournalAppendResult> second = null;
            try {
                first = firstExecutor.submit(
                        () -> storage.append(SESSION_A, List.of(event(1))));
                assertThat(operations.blockedForce.await(10, TimeUnit.SECONDS)).isTrue();

                second = secondExecutor.submit(
                        () -> storage.append(SESSION_A, List.of(event(2))));

                assertThat(operations.contendingAppend.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(operations.actions).containsExactly("write:first-append");

                operations.releaseForce.countDown();
                assertThat(first.get(5, TimeUnit.SECONDS).durableThrough())
                        .contains(new EventId(1));
                assertThat(second.get(5, TimeUnit.SECONDS).durableThrough())
                        .contains(new EventId(2));
                assertThat(operations.actions).containsExactly(
                        "write:first-append",
                        "publish:first-append",
                        "write:second-append",
                        "publish:second-append");
                assertThat(storage.readAfter(SESSION_A, Optional.empty()).records())
                        .containsExactly(event(1), event(2));
            } finally {
                operations.releaseForce.countDown();
                cancel(first);
                cancel(second);
            }
        }
    }

    @Test
    void readUsesTheDurableBoundaryCapturedBeforeAConcurrentAppend() throws Exception {
        SnapshotReadOperations operations = new SnapshotReadOperations("snapshot-reader");
        try (var storage = storage(operations);
                ExecutorService reader = namedExecutor("snapshot-reader")) {
            Future<JournalReadResult> snapshot = null;
            try {
                storage.append(SESSION_A, List.of(event(1)));
                operations.arm();
                snapshot = reader.submit(
                        () -> storage.readAfter(SESSION_A, Optional.empty()));
                assertThat(operations.readStarted.await(10, TimeUnit.SECONDS)).isTrue();

                assertThat(storage.append(SESSION_A, List.of(event(2))).durableThrough())
                        .contains(new EventId(2));
                operations.releaseRead.countDown();

                assertThat(snapshot.get(5, TimeUnit.SECONDS).records()).containsExactly(event(1));
                assertThat(storage.readAfter(SESSION_A, Optional.of(new EventId(1))).records())
                        .containsExactly(event(2));
            } finally {
                operations.releaseRead.countDown();
                cancel(snapshot);
            }
        }
    }

    @Test
    void readSnapshotSurvivesClosedSegmentReplacementAndConcurrentAppend() throws Exception {
        CapturingExecutor maintenance = new CapturingExecutor();
        SnapshotReadOperations operations = new SnapshotReadOperations("snapshot-reader");
        SessionEventRecord first = event(1);
        JournalStorageConfig config = segmentConfig(first.encodedRecord().size());
        try (var storage = FileSystemSessionJournalStorage.withMaintenanceExecutor(
                root, config, operations, maintenance);
                ExecutorService reader = namedExecutor("snapshot-reader")) {
            Future<JournalReadResult> snapshot = null;
            try {
                storage.append(SESSION_A, List.of(first, event(2)));
                maintenance.awaitPending();
                operations.arm();
                snapshot = reader.submit(
                        () -> storage.readAfter(SESSION_A, Optional.empty()));
                assertThat(operations.readStarted.await(10, TimeUnit.SECONDS)).isTrue();

                assertThat(storage.append(SESSION_A, List.of(event(3))).durableThrough())
                        .contains(new EventId(3));
                maintenance.runNext();
                assertThat(root.resolve("session-a/00000001.cbor.zst")).exists();
                assertThat(root.resolve("session-a/00000001.cbor")).exists();
                operations.releaseRead.countDown();

                assertThat(snapshot.get(5, TimeUnit.SECONDS).records())
                        .containsExactly(event(1), event(2));
                assertThat(storage.readAfter(SESSION_A, Optional.of(new EventId(2))).records())
                        .containsExactly(event(3));
                maintenance.awaitPending();
                maintenance.runAll();
                assertThat(storage.maintenanceFailureForTest()).isEmpty();
                assertThat(root.resolve("session-a/00000001.cbor")).doesNotExist();
            } finally {
                operations.releaseRead.countDown();
                cancel(snapshot);
            }
        }
    }

    @Test
    void forceFailurePoisonsOnlyItsSessionAndReopenRecoversItsCompletePrefix() throws Exception {
        FailFirstForceOperations operations = new FailFirstForceOperations();
        try (var storage = storage(operations)) {
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.append(SESSION_A, List.of(event(1))))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);

            assertThat(storage.append(SESSION_B, List.of(event(10))).durableThrough())
                    .contains(new EventId(10));
            assertThat(storage.readAfter(SESSION_B, Optional.empty()).records())
                    .containsExactly(event(10));
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.lastEventId(SESSION_A))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);
        }

        try (var reopened = storage(new DurableFileOperations())) {
            assertThat(reopened.readAfter(SESSION_A, Optional.empty()).records())
                    .containsExactly(event(1));
            assertThat(reopened.lastEventId(SESSION_A)).contains(new EventId(1));
            assertThat(reopened.readAfter(SESSION_B, Optional.empty()).records())
                    .containsExactly(event(10));
        }
    }

    @Test
    void concurrentFirstAccessRejectsOneSessionCaseAlias() throws Exception {
        SessionId upper = new SessionId("Session-Case");
        SessionId lower = new SessionId("session-case");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var storage = storage(new DurableFileOperations());
                ExecutorService upperExecutor = namedExecutor("upper-session");
                ExecutorService lowerExecutor = namedExecutor("lower-session")) {
            Future<Optional<JournalStorageException.Reason>> upperAccess = upperExecutor.submit(
                    () -> firstAccess(storage, upper, ready, start));
            Future<Optional<JournalStorageException.Reason>> lowerAccess = lowerExecutor.submit(
                    () -> firstAccess(storage, lower, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            Optional<JournalStorageException.Reason> upperFailure =
                    upperAccess.get(5, TimeUnit.SECONDS);
            Optional<JournalStorageException.Reason> lowerFailure =
                    lowerAccess.get(5, TimeUnit.SECONDS);
            assertThat(List.of(upperFailure, lowerFailure)).containsExactlyInAnyOrder(
                    Optional.empty(),
                    Optional.of(JournalStorageException.Reason.IO_FAILURE));

            SessionId accepted = upperFailure.isEmpty() ? upper : lower;
            SessionId rejected = upperFailure.isPresent() ? upper : lower;
            assertThat(storage.append(accepted, List.of(event(1))).durableThrough())
                    .contains(new EventId(1));
            assertThatExceptionOfType(JournalStorageException.class)
                    .isThrownBy(() -> storage.lastEventId(rejected))
                    .extracting(JournalStorageException::reason)
                    .isEqualTo(JournalStorageException.Reason.IO_FAILURE);
            assertThat(storage.append(SESSION_B, List.of(event(10))).durableThrough())
                    .contains(new EventId(10));
        } finally {
            start.countDown();
        }
    }

    private FileSystemSessionJournalStorage storage(DurableFileOperations operations) {
        return new FileSystemSessionJournalStorage(
                root, new JournalStorageConfig(AgentProtocolLimits.defaults()), operations);
    }

    private static JournalStorageConfig segmentConfig(long targetBytes) {
        AgentProtocolLimits limits = AgentProtocolLimits.defaults();
        return new JournalStorageConfig(
                limits,
                limits.maxCollectionEntries(),
                limits.maxMessageBytes(),
                targetBytes);
    }

    private static ExecutorService namedExecutor(String name) {
        return Executors.newSingleThreadExecutor(task -> new Thread(task, name));
    }

    private static Optional<JournalStorageException.Reason> firstAccess(
            FileSystemSessionJournalStorage storage,
            SessionId sessionId,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            storage.lastEventId(sessionId);
            return Optional.empty();
        } catch (JournalStorageException e) {
            return Optional.of(e.reason());
        }
    }

    private static void cancel(Future<?> future) {
        if (future != null) {
            future.cancel(true);
        }
    }

    private static void await(CountDownLatch latch, String operation) throws IOException {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting to release " + operation);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting to release " + operation, e);
        }
    }

    private static final class BlockingAppendOperations extends DurableFileOperations {
        private final String blockedThread;
        private final CountDownLatch blockedForce = new CountDownLatch(1);
        private final CountDownLatch releaseForce = new CountDownLatch(1);
        private final CountDownLatch contendingAppend = new CountDownLatch(1);
        private final AtomicBoolean blocked = new AtomicBoolean();
        private final List<String> actions = new CopyOnWriteArrayList<>();
        private final Set<String> writers = ConcurrentHashMap.newKeySet();

        private BlockingAppendOperations(String blockedThread) {
            this.blockedThread = blockedThread;
        }

        @Override
        int write(FileChannel channel, ByteBuffer source) throws IOException {
            String writer = Thread.currentThread().getName();
            if (writers.add(writer)) {
                actions.add("write:" + writer);
            }
            return super.write(channel, source);
        }

        @Override
        void appendLockContended(Path sessionDirectory) {
            if (Thread.currentThread().getName().equals("second-append")) {
                contendingAppend.countDown();
            }
        }

        @Override
        void forceFile(FileChannel channel) throws IOException {
            if (Thread.currentThread().getName().equals(blockedThread)
                    && blocked.compareAndSet(false, true)) {
                blockedForce.countDown();
                await(releaseForce, "file force");
            }
            super.forceFile(channel);
        }

        @Override
        void publishCatalog(Runnable publication) {
            String writer = Thread.currentThread().getName();
            publication.run();
            actions.add("publish:" + writer);
            writers.remove(writer);
        }
    }

    private static final class SnapshotReadOperations extends DurableFileOperations {
        private final String blockedThread;
        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch releaseRead = new CountDownLatch(1);
        private final AtomicBoolean blocked = new AtomicBoolean();
        private volatile boolean armed;

        private SnapshotReadOperations(String blockedThread) {
            this.blockedThread = blockedThread;
        }

        @Override
        void beforeContentRead(Path path) throws IOException {
            if (armed
                    && Thread.currentThread().getName().equals(blockedThread)
                    && blocked.compareAndSet(false, true)) {
                readStarted.countDown();
                await(releaseRead, "snapshot read");
            }
        }

        private void arm() {
            armed = true;
        }
    }

    private static final class FailFirstForceOperations extends DurableFileOperations {
        private final AtomicBoolean fail = new AtomicBoolean(true);

        @Override
        void forceFile(FileChannel channel) throws IOException {
            if (fail.compareAndSet(true, false)) {
                throw new IOException("Injected force failure");
            }
            super.forceFile(channel);
        }
    }

    private static final class CapturingExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public synchronized void execute(Runnable command) {
            tasks.add(command);
            notifyAll();
        }

        private synchronized void awaitPending() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (tasks.isEmpty()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new IllegalStateException("Timed out waiting for maintenance dispatch");
                }
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
        }

        private void runNext() {
            Runnable task;
            synchronized (this) {
                task = tasks.remove();
            }
            task.run();
        }

        private void runAll() {
            while (true) {
                synchronized (this) {
                    if (tasks.isEmpty()) {
                        return;
                    }
                }
                runNext();
            }
        }
    }
}
