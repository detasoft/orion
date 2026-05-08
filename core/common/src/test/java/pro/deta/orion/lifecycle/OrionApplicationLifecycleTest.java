package pro.deta.orion.lifecycle;

import org.junit.jupiter.api.Test;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.internal.OrionThreadFactory;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;
import pro.deta.orion.util.OrionProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class OrionApplicationLifecycleTest {
    @Test
    void waitsForBlockingInitListenerBeforeStartingNextInitListener() {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        List<Consumer<String>> initSubscribers = new CopyOnWriteArrayList<>();

        OrionApplicationStageEventListener aclService = registrar ->
                registrar.register(ApplicationState.INIT, () -> {
                    delayLongEnoughToExposeMissingWait();
                    initSubscribers.add(value -> events.add("acl-handler-received"));
                    events.add("acl-handler-registered");
                    return OrionStageCallResult.EMPTY;
                }).priority(1).waitForCompletion();

        OrionApplicationStageEventListener gitStorage = registrar ->
                registrar.register(ApplicationState.INIT, () -> {
                    events.add("git-storage-event-published");
                    for (Consumer<String> subscriber : initSubscribers) {
                        subscriber.accept("acl-ready");
                    }
                    return OrionStageCallResult.EMPTY;
                }).priority(2);

        try (OrionExecutor executor = new OrionExecutor(4, new OrionThreadFactory())) {
            ApplicationStateHolder stateHolder = new ApplicationStateHolder();
            AtomicReference<OrionApplicationLifecycle> lifecycleRef = new AtomicReference<>();
            OrionProvider provider = new OrionProvider(
                    stateHolder,
                    lifecycleRef::get,
                    OrionEventManager::new,
                    () -> executor);
            OrionApplicationLifecycle lifecycle = new OrionApplicationLifecycle(
                    stateHolder,
                    executor,
                    Set.of(aclService, gitStorage),
                    provider);
            lifecycleRef.set(lifecycle);

            assertThat(lifecycle.runApplication()).isEqualTo(ApplicationState.UP);
        }

        assertThat(events).containsExactly(
                "acl-handler-registered",
                "git-storage-event-published",
                "acl-handler-received");
    }

    private static void delayLongEnoughToExposeMissingWait() throws InterruptedException {
        Thread.sleep(100);
    }
}
