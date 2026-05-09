package pro.deta.orion.git;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;
import pro.deta.orion.config.schema.OrionConfiguration;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Supplies JGit with Orion-managed process data and configuration only.
 * Repository code can still use JGit's normal API surface, but global Git
 * config, JGit config, selected system properties, environment variables,
 * host name, charset, locale and time zone all come from
 * {@link OrionConfiguration.JGitConfig}. This keeps JGit from discovering
 * values through the host user's home directory, XDG config files, JVM
 * properties or shell environment by accident.
 */
@Singleton
public final class ControlledOrionJGitSystemReader extends SystemReader {
    private static final String DEFAULT_HOSTNAME = "localhost";
    private static final String DEFAULT_OS_NAME = "Linux";
    private static final String DEFAULT_CHARSET = "UTF-8";
    private static final String DEFAULT_TIMEZONE = "UTC";

    private final String hostname;
    private final Charset defaultCharset;
    private final TimeZone timeZone;
    private final Locale locale;
    private final Map<String, String> properties;
    private final Map<String, String> environment;
    private final Map<String, String> systemConfig;
    private final Map<String, String> userConfig;
    private final Map<String, String> jgitConfig;

    @Inject
    public ControlledOrionJGitSystemReader(OrionConfiguration configuration) {
        this(configuration.getBootstrap().getJgit());
    }

    public ControlledOrionJGitSystemReader(OrionConfiguration.JGitConfig config) {
        OrionConfiguration.JGitConfig safeConfig = config == null ? new OrionConfiguration.JGitConfig() : config;
        this.hostname = valueOrDefault(safeConfig.getHostname(), DEFAULT_HOSTNAME);
        this.defaultCharset = Charset.forName(valueOrDefault(safeConfig.getDefaultCharset(), DEFAULT_CHARSET));
        this.timeZone = TimeZone.getTimeZone(ZoneId.of(valueOrDefault(safeConfig.getTimezone(), DEFAULT_TIMEZONE)));
        this.locale = parseLocale(safeConfig.getLocale());
        this.properties = controlledProperties(safeConfig);
        this.environment = copy(safeConfig.getEnvironment());
        this.systemConfig = copy(safeConfig.getSystemConfig());
        this.userConfig = copy(safeConfig.getUserConfig());
        this.jgitConfig = copy(safeConfig.getJgitConfig());
    }

    @Override
    public FileBasedConfig openSystemConfig(Config parent, FS fs) {
        return new ApiFileBasedConfig(parent, "system", systemConfig);
    }

    @Override
    public FileBasedConfig openUserConfig(Config parent, FS fs) {
        return new ApiFileBasedConfig(parent, "user", userConfig);
    }

    @Override
    public FileBasedConfig openJGitConfig(Config parent, FS fs) {
        return new ApiFileBasedConfig(parent, "jgit", jgitConfig);
    }

    @Override
    public String getProperty(String key) {
        return properties.get(key);
    }

    @Override
    public String getenv(String variable) {
        return environment.get(variable);
    }

    @Override
    public String getHostname() {
        return hostname;
    }

    @Override
    public long getCurrentTime() {
        return System.currentTimeMillis();
    }

    @Override
    public int getTimezone(long when) {
        return getTimeZone().getOffset(when) / (60 * 1000);
    }

    @Override
    public TimeZone getTimeZone() {
        return (TimeZone) timeZone.clone();
    }

    @Override
    public Locale getLocale() {
        return locale;
    }

    @Override
    public Charset getDefaultCharset() {
        return defaultCharset;
    }

    @Override
    public SimpleDateFormat getSimpleDateFormat(String pattern) {
        return configure(new SimpleDateFormat(pattern, locale));
    }

    @Override
    public SimpleDateFormat getSimpleDateFormat(String pattern, Locale locale) {
        return configure(new SimpleDateFormat(pattern, locale));
    }

    @Override
    public DateFormat getDateTimeInstance(int dateStyle, int timeStyle) {
        DateFormat dateFormat = DateFormat.getDateTimeInstance(dateStyle, timeStyle, locale);
        dateFormat.setTimeZone(timeZone);
        return dateFormat;
    }

    @Override
    public Path getXdgConfigDirectory(FS fileSystem) {
        String configHomePath = getenv(Constants.XDG_CONFIG_HOME);
        if (configHomePath == null || configHomePath.isBlank()) {
            return null;
        }
        try {
            return Path.of(configHomePath);
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private SimpleDateFormat configure(SimpleDateFormat dateFormat) {
        dateFormat.setTimeZone(timeZone);
        return dateFormat;
    }

    private static Map<String, String> controlledProperties(OrionConfiguration.JGitConfig config) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("os.name", valueOrDefault(config.getOsName(), DEFAULT_OS_NAME));
        properties.put("native.encoding", valueOrDefault(config.getDefaultCharset(), DEFAULT_CHARSET));
        properties.putAll(copy(config.getProperties()));
        return properties;
    }

    private static Locale parseLocale(String value) {
        if (value == null || value.isBlank() || "und".equals(value)) {
            return Locale.ROOT;
        }
        return Locale.forLanguageTag(value);
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static Map<String, String> copy(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(values);
    }

    private static final class ApiFileBasedConfig extends FileBasedConfig {
        private final String name;

        private ApiFileBasedConfig(Config parent, String name, Map<String, String> values) {
            super(parent, null, null);
            this.name = name;
            apply(values);
        }

        @Override
        public void load() {
        }

        @Override
        public boolean isOutdated() {
            return false;
        }

        @Override
        protected byte[] readIncludedConfig(String relPath) {
            return null;
        }

        @Override
        public void save() throws IOException {
        }

        @Override
        public String toString() {
            return "ApiFileBasedConfig[" + name + "]";
        }

        private void apply(Map<String, String> values) {
            for (Map.Entry<String, String> entry : values.entrySet()) {
                apply(entry.getKey(), entry.getValue());
            }
        }

        private void apply(String key, String value) {
            String[] parts = key.split("\\.");
            if (parts.length < 2) {
                throw new IllegalArgumentException("JGit config key must be section.name or section.subsection.name: " + key);
            }

            String section = parts[0];
            String name = parts[parts.length - 1];
            if (section.isBlank() || name.isBlank()) {
                throw new IllegalArgumentException("JGit config key has an empty section or name: " + key);
            }

            String subsection = null;
            if (parts.length > 2) {
                StringBuilder builder = new StringBuilder();
                for (int i = 1; i < parts.length - 1; i++) {
                    if (i > 1) {
                        builder.append('.');
                    }
                    builder.append(parts[i]);
                }
                subsection = builder.toString();
                if (subsection.isBlank()) {
                    throw new IllegalArgumentException("JGit config key has an empty subsection: " + key);
                }
            }

            setString(section, subsection, name, value);
        }
    }
}
