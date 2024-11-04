package pro.deta.orion.event;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.EventTranslator;
import com.lmax.disruptor.TimeoutException;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.util.DaemonThreadFactory;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.event.disruptor.EventHolder;
import pro.deta.orion.event.disruptor.OrionDisruptor;
import pro.deta.orion.event.type.OrionEvent;
import pro.deta.orion.lifecycle.*;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Singleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class OrionEventManager implements OrionApplicationStageEventListener {
    public static final int RING_BUFFER_SIZE = 32_768;
    private final OrionDisruptor rootDisruptor = new OrionDisruptor(RING_BUFFER_SIZE);
    private final Map<String, List<Consumer<OrionEvent>>> handlersMap = new ConcurrentHashMap<>();

    @Override
    public void registerToStage(ApplicationStateListenerRegistrar registrar) {
        registrar.register(ApplicationState.INIT, this::onInit).priority(-1).waitForCompletionSecs(2);
        registrar.register(ApplicationState.STOPPING, this::onStop).priority(90);
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

}
