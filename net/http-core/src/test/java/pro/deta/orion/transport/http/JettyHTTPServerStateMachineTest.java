package pro.deta.orion.transport.http;

import org.junit.jupiter.api.Test;
import pro.deta.orion.keymaterial.OrionKeyMaterial;
import pro.deta.orion.keymaterial.KeyMaterialChanged;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.internal.OrionThreadFactory;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.bootstrap.config.BootstrapConfiguration;
import pro.deta.orion.schema.orion.v2.OrionDocument;

import pro.deta.orion.test.integration.TestConfiguration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.ArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.assertj.core.api.Assertions.assertThat;

class JettyHTTPServerStateMachineTest {
    private static final pro.deta.orion.test.util.TestEvents EVENTS =
            new pro.deta.orion.test.util.TestEvents();

    @org.junit.jupiter.api.AfterEach
    void stopTestEvents() {
        EVENTS.close();
    }

    @Test
    void stateMachineDefinitionComesFromGenericServiceAdapter() {
        TestConfiguration desiredState = new TestConfiguration(EVENTS);
        desiredState.publish(new OrionDocument(
                new OrionDocument.SystemConfiguration(new AccessControl(), Optional.empty(), List.of(), List.of(), List.of(),
                List.of(), List.of()),
                List.of()), Optional.of("test-revision"));
        JettyHTTPServer server =
                new JettyHTTPServer(
                        new BootstrapConfiguration(),
                        desiredState.editor(),
                        OrionKeyMaterial.unavailable(),
                        null,
                        null,
                        null);
        JettyHTTPServerStateMachine machine = new JettyHTTPServerStateMachine(() -> server,
                () -> { throw new AssertionError(); }, EVENTS.rawManager(), EVENTS.executor());

        assertEquals("http", machine.stateMachine().name());
        assertEquals(Set.of(machine.startAction().id(), machine.stopAction().id()), machine.stateMachine().availableActions());
    }

    @Test
    void retriesActivationAndCancelsOnlyItsOwnFutureOnStop() throws Exception {
        try (RecordingScheduler scheduler = new RecordingScheduler();
                NativeGitRepositoryProvider repositories =
                        NativeGitRepositoryProvider.inMemory(EVENTS.manager())) {
            AtomicInteger attempts = new AtomicInteger();
            JettyHTTPServer server = new JettyHTTPServer(
                    new BootstrapConfiguration(), null, OrionKeyMaterial.unavailable(), null, null, null) {
                private boolean running;

                @Override public void onStart() { running = true; }
                @Override public void onStop() { running = false; }
                @Override public boolean isEnabled() { return true; }
                @Override public boolean isRunning() { return running; }
                @Override boolean usesKeyMaterialResources(Set<String> resources) {
                    return resources.contains("https");
                }
                @Override void reloadHttpsCertificate() {
                    if (attempts.incrementAndGet() == 1) throw new IllegalStateException("Activation failed");
                }
            };
            GitPackCleanupTask cleanup = new GitPackCleanupTask(repositories);
            JettyHTTPServerStateMachine machine = new JettyHTTPServerStateMachine(
                    () -> server, () -> cleanup, EVENTS.manager(), scheduler);
            assertThat(machine.start().failed()).isFalse();
            try {
                EVENTS.manager().publishAndWait(new KeyMaterialChanged(Set.of("https")));
                assertThat(scheduler.tasks).hasSize(1);
                assertThat(scheduler.tasks.getFirst().delay()).isZero();
                scheduler.tasks.getFirst().command().run();
                assertThat(scheduler.tasks).hasSize(2);
                Scheduled retry = scheduler.tasks.getLast();
                assertThat(retry.delay()).isEqualTo(60);
                assertThat(retry.unit()).isEqualTo(TimeUnit.SECONDS);
                retry.command().run();
                assertThat(attempts).hasValue(2);
            } finally {
                assertThat(machine.stop().failed()).isFalse();
            }
            assertThat(scheduler.tasks.getLast().future().isCancelled()).isTrue();
            EVENTS.manager().publishAndWait(new KeyMaterialChanged(Set.of("https")));
            assertThat(scheduler.tasks).hasSize(2);
            assertThat(scheduler.submit(() -> "still running").get(5, TimeUnit.SECONDS))
                    .isEqualTo("still running");
        }
    }

    private record Scheduled(Runnable command, long delay, TimeUnit unit, ScheduledFuture<?> future) {}

    private static final class RecordingScheduler extends OrionExecutor {
        private final List<Scheduled> tasks = new ArrayList<>();

        private RecordingScheduler() {
            super(2, new OrionThreadFactory());
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            ScheduledFuture<?> future = super.schedule(command, 1, TimeUnit.DAYS);
            tasks.add(new Scheduled(command, delay, unit, future));
            return future;
        }
    }
}
