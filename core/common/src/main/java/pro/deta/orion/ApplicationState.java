package pro.deta.orion;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ApplicationState {
    INIT(0), STARTING(1), UP(2), BEGIN_SHUTDOWN(3), STOPPING(4), OFF(5), FAILED(6);

    private final int level;

    public boolean appIsActive() {
        return getLevel() < 3;
    }

    public boolean appIsUp() {
        return getLevel() == UP.getLevel();
    }

    public boolean appIsShutdown() {
        return getLevel() == OFF.getLevel();
    }
}
