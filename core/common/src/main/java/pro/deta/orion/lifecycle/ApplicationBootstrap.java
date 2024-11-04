package pro.deta.orion.lifecycle;

import lombok.Getter;
import pro.deta.orion.util.LogInitializer;

@Getter
public class ApplicationBootstrap {
    private final LogInitializer logInitializer = new LogInitializer();

}
