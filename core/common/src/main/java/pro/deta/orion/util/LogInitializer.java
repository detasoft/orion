package pro.deta.orion.util;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Getter
public class LogInitializer {
    private static final String TEST_DEBUG_PROPERTY = "orion.test.debug";
    private static final String TEST_LOG_LEVEL_PROPERTY = "orion.test.log.level";
    private static final String TEST_LOG_CATEGORIES_PROPERTY = "orion.test.log.categories";
    private static final String DEFAULT_TEST_LOG_LEVEL = "DEBUG";
    private static final String DEFAULT_TEST_LOG_CATEGORIES = "pro.deta.orion";

    private final List<String> categoryLevels = new ArrayList<>();
    private final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

    public LogInitializer() {
        categoryLevels.add(":INFO");
        categoryLevels.add("org.apache.sshd.server.channel.PipeDataReceiver:WARN");
        categoryLevels.add("org.apache.sshd.server.session:WARN");
        categoryLevels.add("pro.deta.orion.git.util.GitUtils:TRACE");
        categoryLevels.add("org.eclipse.jetty:WARN");
        categoryLevels.addAll(testDebugLevels());
        reconfigure();
    }

    private void reconfigure() {
        // Get the LoggerContext

        // Reset any existing configuration
        context.reset();

        // Create encoder
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} -%kvp- %msg%n");
        encoder.start();

        // Create console appender
        ConsoleAppender<ILoggingEvent> consoleAppender = new ConsoleAppender<>();
        consoleAppender.setContext(context);
        consoleAppender.setName("CONSOLE");
        consoleAppender.setEncoder(encoder);
        consoleAppender.start();

        for (String category: categoryLevels) {
            String[] vals = category.split(":");
            if (vals.length < 2) {
                log.error("Category {} is not suitable for configure, skipping.", category);
            } else {
                Logger logger = getLogger(vals[0]);
                logger.setLevel(Level.valueOf(vals[1]));
            }
        }
        getLogger(null).addAppender(consoleAppender);
    }

    private Logger getLogger(String category) {
        if (category == null || "".equalsIgnoreCase(category))
            return context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        else
            return context.getLogger(category);
    }

    public void configure() {
        reconfigure();
    }

    public void setLevel(String s, String level) {
        categoryLevels.add(s+ ":" + level);
        configure();
    }

    private static List<String> testDebugLevels() {
        if (!Boolean.parseBoolean(System.getProperty(TEST_DEBUG_PROPERTY, "false"))) {
            return List.of();
        }

        String defaultLevel = System.getProperty(TEST_LOG_LEVEL_PROPERTY, DEFAULT_TEST_LOG_LEVEL);
        String categories = System.getProperty(TEST_LOG_CATEGORIES_PROPERTY, DEFAULT_TEST_LOG_CATEGORIES);
        if (categories == null || categories.isBlank()) {
            categories = DEFAULT_TEST_LOG_CATEGORIES;
        }

        List<String> levels = new ArrayList<>();
        for (String entry : categories.split("[,;]")) {
            String level = entryToCategoryLevel(entry, defaultLevel);
            if (!level.isBlank()) {
                levels.add(level);
            }
        }
        return levels;
    }

    private static String entryToCategoryLevel(String raw, String defaultLevel) {
        String entry = raw.trim();
        if (entry.isEmpty()) {
            return "";
        }

        int separator = separatorIndex(entry);
        if (separator < 0) {
            return normalizeCategory(entry) + ":" + defaultLevel;
        }

        return normalizeCategory(entry.substring(0, separator).trim()) + ":" + entry.substring(separator + 1).trim();
    }

    private static int separatorIndex(String entry) {
        int equalsIndex = entry.indexOf('=');
        int colonIndex = entry.indexOf(':');
        if (equalsIndex < 0) {
            return colonIndex;
        }
        if (colonIndex < 0) {
            return equalsIndex;
        }
        return Math.min(equalsIndex, colonIndex);
    }

    private static String normalizeCategory(String category) {
        if ("ROOT".equalsIgnoreCase(category)) {
            return "";
        }
        return category;
    }
}
