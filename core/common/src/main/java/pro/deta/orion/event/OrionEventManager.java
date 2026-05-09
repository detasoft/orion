package pro.deta.orion.event;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.TimeoutException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.event.disruptor.EventHolder;
import pro.deta.orion.event.disruptor.OrionDisruptor;
import pro.deta.orion.event.type.OrionEvent;
import pro.deta.orion.lifecycle.*;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;
import pro.deta.orion.lifecycle.task.OrionLifecycleTasks;
import pro.deta.orion.util.OrionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Application-wide asynchronous event bus for Orion events.
 *
 * <p>An event is a concrete {@link OrionEvent} subclass that describes something that already happened or must be
 * reacted to elsewhere in the application. Current examples include git receive/upload notifications and ACL reload
 * requests. Events should carry the data their handlers need, because handlers run later and
 * outside the original request flow.</p>
 *
 * <p>The manager owns a single root Disruptor and starts/stops it with the application lifecycle. Event dispatch is
 * intentionally simple: handlers are registered by the concrete event class and are invoked only for that exact class.
 * There is no superclass/interface dispatch.</p>
 *
 * <p>Handlers run on the Disruptor daemon thread, so they should be quick and should not rely on request-thread
 * ThreadLocals. If a handler needs request context, the publisher must copy the required data into the event object.</p>
 */
@Singleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class OrionEventManager implements OrionApplicationStageEventListener {
    public static final int RING_BUFFER_SIZE = 32_768;
    private final OrionDisruptor rootDisruptor = new OrionDisruptor(RING_BUFFER_SIZE);
    private final Map<String, List<Consumer<OrionEvent>>> handlersMap = new ConcurrentHashMap<>();

    @Override
    public void registerToStage(ApplicationStateListenerRegistrar registrar) {
        registrar.task(this, ApplicationState.INIT, OrionLifecycleTasks.EVENT_MANAGER, this::onInit)
                .after(OrionLifecycleTasks.JGIT_RUNTIME)
                .waitForCompletionSecs(2);
        registrar.task(this, ApplicationState.STOPPING, OrionLifecycleTasks.EVENT_MANAGER_STOP, this::onStop)
                .after(OrionLifecycleTasks.HTTP_TRANSPORT_STOP)
                .after(OrionLifecycleTasks.GIT_TRANSPORT_STOP)
                .after(OrionLifecycleTasks.SSH_TRANSPORT_STOP);
    }

    public OrionStageCallResult onInit() {
        rootDisruptor.handleEventsWith(new EventHandler<EventHolder>() {
            @Override
            public void onEvent(EventHolder eventHolder, long l, boolean b) {
                handleEvent(eventHolder.getOrionEvent());
                eventHolder.getOrionEvent().setProcessed();
            }
        });
        rootDisruptor.start();
        // start and wait until all init-registered tasks are finished (tech debt for init stage)

        return null;
    }

    public long getUnprocessedLength() {
        return rootDisruptor.getUnprocessedLength();
    }

    public OrionStageCallResult onStop() {
        try {
            rootDisruptor.stop();
        } catch (TimeoutException e) {
            log.error("Error while stopping Disruptor: ",e);
        }
        return null;
    }

    private void handleEvent(OrionEvent event) {
        List<Consumer<OrionEvent>> handlers = handlersMap.get(event.getClass().getCanonicalName());
        if (handlers == null)
            return;
        for (Consumer<OrionEvent> c: handlers) {
            c.accept(event);
        }
    }

    public <T extends OrionEvent> void registerTypeHandler(Class<T> classCanonicalName, Consumer<T> consumer) {
        //noinspection unchecked
        handlersMap.computeIfAbsent(classCanonicalName.getCanonicalName(), (k) -> new ArrayList<>()).add((Consumer<OrionEvent>) consumer);
    }

    public void publish(Supplier<OrionEvent> eventPublisher) {
        rootDisruptor.publish(eventPublisher);
    }

    public void publish(OrionEvent orionEvent) {
        rootDisruptor.publish(orionEvent);
    }
    public void publishAndWait(OrionEvent orionEvent) {
        publish(orionEvent);
        if (!OrionUtils.waitForCondition(orionEvent::isProcessed)) {
            throw new IllegalStateException("Timed out waiting for ACL reload event: " + orionEvent);
        }
    }
}
