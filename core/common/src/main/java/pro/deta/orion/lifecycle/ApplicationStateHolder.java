package pro.deta.orion.lifecycle;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.util.LogUtils;

import java.util.concurrent.atomic.AtomicReference;

@ToString
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class ApplicationStateHolder {
    private final AtomicReference<ApplicationState> state = new AtomicReference<>(ApplicationState.INIT);

    public void moveStateFrom(ApplicationState old, ApplicationState current) {
        if(!state.compareAndSet(old, current))
            throw new IllegalStateException(LogUtils.formatMessage("Trying to move application {} -> {} but current state is {}", old, current, state.get()));
    }

    public boolean isActive() {
        return state.get().appIsActive();
    }

    public boolean isUp() {
        return state.get().appIsUp();
    }

    public ApplicationState getState() {
        return state.get();
    }

    public boolean isShutdown() {
        return state.get().appIsShutdown();
    }
}
