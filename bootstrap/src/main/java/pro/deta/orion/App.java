package pro.deta.orion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.config.AppConfigContext;

import java.io.IOException;

@Slf4j
public class App {
    public static void main(String[] args) throws IOException {
        ObjectMapper yom = new ObjectMapper(new YAMLFactory());
        AppConfigContext.AppConfiguration appConfiguration = yom.readerFor(AppConfigContext.AppConfiguration.class)
                .readValue(Thread.currentThread().getContextClassLoader().getResourceAsStream("config.yml"));
        log.warn("OrionApp started with {}", appConfiguration);
        new OrionApp(appConfiguration).start();
    }
}
