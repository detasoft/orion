package pro.deta.orion.util;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class LogInitializerTest {
    private static final String TEST_DEBUG_PROPERTY = "orion.test.debug";
    private static final String TEST_LOG_LEVEL_PROPERTY = "orion.test.log.level";
    private static final String TEST_LOG_CATEGORIES_PROPERTY = "orion.test.log.categories";

    private final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    private String originalDebugProperty;
    private String originalLevelProperty;
    private String originalCategoriesProperty;

    @BeforeEach
    void rememberOriginalProperties() {
        originalDebugProperty = System.getProperty(TEST_DEBUG_PROPERTY);
        originalLevelProperty = System.getProperty(TEST_LOG_LEVEL_PROPERTY);
        originalCategoriesProperty = System.getProperty(TEST_LOG_CATEGORIES_PROPERTY);
    }

    @AfterEach
    void restoreDefaultLogging() {
        restoreProperty(TEST_DEBUG_PROPERTY, originalDebugProperty);
        restoreProperty(TEST_LOG_LEVEL_PROPERTY, originalLevelProperty);
        restoreProperty(TEST_LOG_CATEGORIES_PROPERTY, originalCategoriesProperty);
        new LogInitializer();
    }

    @Test
    void appliesUnitTestDebugPropertiesWhenReinitializingLogging() {
        System.setProperty(TEST_DEBUG_PROPERTY, "true");
        System.setProperty(TEST_LOG_LEVEL_PROPERTY, "TRACE");
        System.setProperty(TEST_LOG_CATEGORIES_PROPERTY,
                "pro.deta.orion.git,org.eclipse.jgit=WARN,ROOT:ERROR");

        new LogInitializer();

        assertThat(logger("pro.deta.orion.git").getLevel()).isEqualTo(Level.TRACE);
        assertThat(logger("org.eclipse.jgit").getLevel()).isEqualTo(Level.WARN);
        assertThat(logger(org.slf4j.Logger.ROOT_LOGGER_NAME).getLevel()).isEqualTo(Level.ERROR);
    }

    private Logger logger(String name) {
        return context.getLogger(name);
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
            return;
        }
        System.setProperty(name, value);
    }
}
