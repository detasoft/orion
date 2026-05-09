package pro.deta.orion.test.duration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestPlan;
import org.slf4j.LoggerFactory;

public final class TestLoggingConfigurer implements TestExecutionListener {
    static final String DEBUG_PROPERTY = "orion.test.debug";
    static final String LEVEL_PROPERTY = "orion.test.log.level";
    static final String CATEGORIES_PROPERTY = "orion.test.log.categories";
    static final String DEFAULT_LEVEL = "DEBUG";
    static final String DEFAULT_CATEGORIES = "pro.deta.orion";

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        configureFromProperties();
    }

    static void configureFromProperties() {
        if (!Boolean.parseBoolean(System.getProperty(DEBUG_PROPERTY, "false"))) {
            return;
        }

        Level defaultLevel = parseLevel(System.getProperty(LEVEL_PROPERTY, DEFAULT_LEVEL));
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        for (String entry : categories()) {
            if (entry.isBlank()) {
                continue;
            }
            LoggerLevel loggerLevel = parseLoggerLevel(entry, defaultLevel);
            Logger logger = context.getLogger(loggerLevel.loggerName());
            logger.setLevel(loggerLevel.level());
        }
    }

    private static String[] categories() {
        String categories = System.getProperty(CATEGORIES_PROPERTY, DEFAULT_CATEGORIES);
        if (categories == null || categories.isBlank()) {
            categories = DEFAULT_CATEGORIES;
        }
        return categories.split("[,;]");
    }

    private static LoggerLevel parseLoggerLevel(String raw, Level defaultLevel) {
        String entry = raw.trim();
        if (entry.isEmpty()) {
            return new LoggerLevel(DEFAULT_CATEGORIES, defaultLevel);
        }

        int separator = separatorIndex(entry);
        if (separator < 0) {
            return new LoggerLevel(normalizeLoggerName(entry), defaultLevel);
        }

        String loggerName = normalizeLoggerName(entry.substring(0, separator).trim());
        Level level = parseLevel(entry.substring(separator + 1).trim());
        return new LoggerLevel(loggerName, level);
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

    private static String normalizeLoggerName(String loggerName) {
        if (loggerName == null || loggerName.isBlank() || "ROOT".equalsIgnoreCase(loggerName)) {
            return org.slf4j.Logger.ROOT_LOGGER_NAME;
        }
        return loggerName;
    }

    private static Level parseLevel(String level) {
        if (level == null || level.isBlank()) {
            return Level.valueOf(DEFAULT_LEVEL);
        }
        return Level.valueOf(level.trim().toUpperCase());
    }

    private record LoggerLevel(String loggerName, Level level) {
    }
}
