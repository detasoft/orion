package pro.deta.orion.test.duration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TestLoggingConfigurerTest {
    private final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    private Level originalOrionLevel;
    private Level originalOrionGitLevel;
    private Level originalJGitLevel;
    private Level originalRootLevel;
    private String originalDebugProperty;
    private String originalLevelProperty;
    private String originalCategoriesProperty;

    @BeforeEach
    void rememberOriginalState() {
        originalOrionLevel = logger("pro.deta.orion").getLevel();
        originalOrionGitLevel = logger("pro.deta.orion.git").getLevel();
        originalJGitLevel = logger("org.eclipse.jgit").getLevel();
        originalRootLevel = logger(org.slf4j.Logger.ROOT_LOGGER_NAME).getLevel();
        originalDebugProperty = System.getProperty(TestLoggingConfigurer.DEBUG_PROPERTY);
        originalLevelProperty = System.getProperty(TestLoggingConfigurer.LEVEL_PROPERTY);
        originalCategoriesProperty = System.getProperty(TestLoggingConfigurer.CATEGORIES_PROPERTY);
    }

    @AfterEach
    void restorePropertiesAndLevels() {
        restoreProperty(TestLoggingConfigurer.DEBUG_PROPERTY, originalDebugProperty);
        restoreProperty(TestLoggingConfigurer.LEVEL_PROPERTY, originalLevelProperty);
        restoreProperty(TestLoggingConfigurer.CATEGORIES_PROPERTY, originalCategoriesProperty);
        logger("pro.deta.orion").setLevel(originalOrionLevel);
        logger("pro.deta.orion.git").setLevel(originalOrionGitLevel);
        logger("org.eclipse.jgit").setLevel(originalJGitLevel);
        logger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(originalRootLevel);
    }

    @Test
    void disabledByDefault() {
        System.clearProperty(TestLoggingConfigurer.DEBUG_PROPERTY);
        System.clearProperty(TestLoggingConfigurer.LEVEL_PROPERTY);
        System.clearProperty(TestLoggingConfigurer.CATEGORIES_PROPERTY);
        logger("pro.deta.orion").setLevel(null);

        TestLoggingConfigurer.configureFromProperties();

        assertNull(logger("pro.deta.orion").getLevel());
    }

    @Test
    void enablesDefaultOrionDebugCategory() {
        System.setProperty(TestLoggingConfigurer.DEBUG_PROPERTY, "true");

        TestLoggingConfigurer.configureFromProperties();

        assertEquals(Level.DEBUG, logger("pro.deta.orion").getLevel());
    }

    @Test
    void supportsCustomLevelsAndCategories() {
        System.setProperty(TestLoggingConfigurer.DEBUG_PROPERTY, "true");
        System.setProperty(TestLoggingConfigurer.LEVEL_PROPERTY, "TRACE");
        System.setProperty(TestLoggingConfigurer.CATEGORIES_PROPERTY,
                "pro.deta.orion.git,org.eclipse.jgit=WARN,ROOT:ERROR");

        TestLoggingConfigurer.configureFromProperties();

        assertEquals(Level.TRACE, logger("pro.deta.orion.git").getLevel());
        assertEquals(Level.WARN, logger("org.eclipse.jgit").getLevel());
        assertEquals(Level.ERROR, logger(org.slf4j.Logger.ROOT_LOGGER_NAME).getLevel());
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
