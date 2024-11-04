package pro.deta.orion.internal;

import lombok.extern.slf4j.Slf4j;

import java.io.FileInputStream;
import java.nio.file.Path;
import java.util.Properties;

@Slf4j
public class OrionProperties {
    private static final Properties _properties = new Properties();

    public static void set(String key, String value) {
        _properties.put(key, value);
    }

    public static void load(Path location) {
        if (location.toFile().exists()) {
            try {
                _properties.load(new FileInputStream(location.toFile()));
            } catch (Exception e) {
                log.warn("Error while loading orion internal properties.", e);
            }
        }
    }

    public static boolean get(String systemPropertyName, boolean defaultValue) {
        String value = System.getProperty(systemPropertyName);
        if (value == null)
            return defaultValue;
        else
            return Boolean.parseBoolean(value);

    }
}
