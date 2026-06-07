package pro.deta.orion.util;

import jakarta.inject.Inject;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.lifecycle.OrionApplicationLifecycle;

import jakarta.inject.Provider;


@Data
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class OrionProvider {
    private final Provider<OrionApplicationLifecycle> orionApplicationLifecycle;
    private final Provider<OrionEventManager> eventManager;
    private final Provider<OrionExecutor> orionExecutor;

    public OrionApplicationLifecycle getOrionApplicationLifecycle() {
        return orionApplicationLifecycle.get();
    }

    public OrionEventManager getEventManager() {
        return eventManager.get();
    }

    public OrionExecutor getOrionExecutor() {
        return orionExecutor.get();
    }
}
