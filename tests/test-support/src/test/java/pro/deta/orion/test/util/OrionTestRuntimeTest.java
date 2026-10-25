package pro.deta.orion.test.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.event.type.OrionEvent;
import pro.deta.orion.internal.OrionExecutor;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

class OrionTestRuntimeTest {
    @Test
    void drainsEventsBeforeClosingTheirConsumersAndStopsTheDispatcher() {
        List<String> operations = new ArrayList<>();
        OrionTestRuntime events = new OrionTestRuntime();
        OrionEventManager manager = events.events();
        manager.subscribe(Message.class, ignored -> operations.add("event"));
        events.own(() -> operations.add("close"));
        manager.publish(new Message());

        events.close();

        assertThat(operations).containsExactly("event", "close");
        assertThat(manager.isRunning()).isFalse();
    }

    @Test
    void closesEveryResourceInReverseOrderEvenWhenOneFails() {
        List<String> operations = new ArrayList<>();
        OrionTestRuntime events = new OrionTestRuntime();
        OrionEventManager manager = events.events();
        events.own(() -> operations.add("first"));
        events.own(() -> {
            operations.add("second");
            throw new IOException("resource failure");
        });
        events.own(() -> operations.add("third"));

        assertThatThrownBy(events::close).isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(IOException.class);

        assertThat(operations).containsExactly("third", "second", "first");
        assertThat(manager.isRunning()).isFalse();
        events.close();
    }

    @Test
    void closingOneRuntimeDoesNotStopAnother() throws Exception {
        try (OrionTestRuntime first = new OrionTestRuntime();
                OrionTestRuntime second = new OrionTestRuntime()) {
            OrionEventManager firstEvents = first.events();
            OrionEventManager secondEvents = second.events();
            OrionExecutor firstExecutor = first.executor();
            OrionExecutor secondExecutor = second.executor();
            assertThat(firstEvents).isNotSameAs(secondEvents);
            assertThat(firstExecutor).isNotSameAs(secondExecutor);

            first.close();

            assertThat(firstEvents.isRunning()).isFalse();
            assertThat(firstExecutor.isShutdown()).isTrue();
            assertThat(secondEvents.isRunning()).isTrue();
            assertThat(secondExecutor.submit(() -> "running").get(5, TimeUnit.SECONDS)).isEqualTo("running");
            secondEvents.publishAndWait(new Message());
        }
    }

    @Test
    void extensionClosesResourcesAfterSuccessfulAndFailedParallelTests() {
        ExtensionFixture.completed.clear();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        LauncherFactory.create().execute(LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(ExtensionFixture.class))
                .configurationParameter("junit.jupiter.execution.parallel.enabled", "true")
                .configurationParameter("junit.jupiter.execution.parallel.mode.default", "concurrent")
                .configurationParameter("junit.jupiter.execution.parallel.config.strategy", "fixed")
                .configurationParameter("junit.jupiter.execution.parallel.config.fixed.parallelism", "2")
                .build(), listener);

        assertThat(listener.getSummary().getTestsSucceededCount()).isEqualTo(1);
        assertThat(listener.getSummary().getTestsFailedCount()).isEqualTo(1);
        assertThat(listener.getSummary().getFailures().getFirst().getException())
                .isInstanceOf(IllegalStateException.class).hasMessage("fixture failure");
        assertThat(ExtensionFixture.completed).containsOnlyKeys("successful", "failed");
        for (Resources resources : ExtensionFixture.completed.values()) {
            assertThat(resources.operations()).containsExactly("event", "close");
            assertThat(resources.events().isRunning()).isFalse();
            assertThat(resources.executor().isShutdown()).isTrue();
        }
    }

    static class ExtensionFixture {
        private static final Map<String, Resources> completed = new ConcurrentHashMap<>();

        @RegisterExtension
        final OrionTestRuntime runtime = new OrionTestRuntime();

        @Test
        void successful() {
            useRuntime("successful");
        }

        @Test
        void failed() {
            useRuntime("failed");
            throw new IllegalStateException("fixture failure");
        }

        private void useRuntime(String name) {
            List<String> operations = new ArrayList<>();
            OrionEventManager events = runtime.events();
            Resources resources = new Resources(events, runtime.executor(), operations);
            events.subscribe(Message.class, ignored -> operations.add("event"));
            runtime.own(() -> {
                operations.add("close");
                completed.put(name, resources);
            });
            events.publish(new Message());
        }
    }

    private record Resources(OrionEventManager events, OrionExecutor executor, List<String> operations) {}

    private static final class Message extends OrionEvent {}
}
