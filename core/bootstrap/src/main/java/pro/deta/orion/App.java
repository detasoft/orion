package pro.deta.orion;

import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.component.DaggerOrionComponent;
import pro.deta.orion.component.OrionComponent;
import pro.deta.orion.config.FileConfigurationProviderImpl;

import java.io.IOException;

@Slf4j
public class App {
    public static void main(String[] args) throws IOException {
        OrionComponent orionComponent = DaggerOrionComponent.builder()
                .configurationProvider(new FileConfigurationProviderImpl())
                .build();
        orionComponent.orionApplicationLifecycle().runApplication();
    }
}
