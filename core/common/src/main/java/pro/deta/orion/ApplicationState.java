package pro.deta.orion;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ApplicationState {
    INIT(0), STARTING(1), UP(2), STOPPING(3), OFF(4), FAILED(5);

    private final int level;

    public boolean appIsActive() {
        return getLevel() < STOPPING.getLevel();
    }

    public boolean appIsUp() {
        return getLevel() == UP.getLevel();
    }

    public boolean appIsShutdown() {
        return getLevel() == OFF.getLevel();
    }
}
