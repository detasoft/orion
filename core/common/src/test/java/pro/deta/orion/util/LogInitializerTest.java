package pro.deta.orion.util;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogInitializerTest {
    @Test
    void returnsExactlyTheConsoleEncoderOutputIncludingMultilineExceptionsAndKeyValues() {
        LogInitializer logging = new LogInitializer();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        @SuppressWarnings("unchecked")
        ConsoleAppender<ILoggingEvent> console = (ConsoleAppender<ILoggingEvent>)
                logger(org.slf4j.Logger.ROOT_LOGGER_NAME).getAppender("CONSOLE");
        console.setOutputStream(output);
        String cursor = logging.readLogs(null).cursor();

        Object changingValue = new Object() {
            private int calls;

            @Override
            public String toString() {
                return "formatted-" + ++calls;
            }
        };
        logger("server.logs.test").atInfo().addKeyValue("operation", "read")
                .addKeyValue("changing", changingValue).log("Строка {}\nsecond", 1);
        logger("server.logs.test").error("Failure", new IllegalStateException("synthetic failure"));

        LogInitializer.LogPage page = logging.readLogs(cursor);
        assertThat(page.entries()).extracting(LogInitializer.LogEntry::level).containsExactly("INFO", "ERROR");
        StringBuilder combined = new StringBuilder();
        for (LogInitializer.LogEntry entry : page.entries()) combined.append(entry.text());
        assertThat(combined.toString()).isEqualTo(output.toString(StandardCharsets.UTF_8));
        assertThat(combined.toString()).contains("operation=\"read\"", "Строка 1\nsecond",
                "java.lang.IllegalStateException: synthetic failure", "LogInitializerTest.java:");
        assertThat(page.gap()).isFalse();
        assertThat(logging.readLogs(page.cursor()).entries()).isEmpty();
    }

    @Test
    void retainsHistoryAndCapturesNewLevelsOnceAfterReconfiguration() {
        LogInitializer logging = new LogInitializer();
        logger("server.logs.test").info("before configuration");
        logger("server.logs.test").debug("filtered out");
        logging.setLevel("server.logs.test", "DEBUG");
        logger("server.logs.test").debug("after configuration");

        assertThat(logging.readLogs(null).entries()).extracting(LogInitializer.LogEntry::text)
                .satisfiesExactly(
                        text -> assertThat(text).contains("before configuration"),
                        text -> assertThat(text).contains("after configuration"));
    }

    @Test
    void boundsHistoryAndReportsEvictionWithoutTruncatingRetainedMessages() {
        LogInitializer logging = new LogInitializer();
        silenceConsole();
        String cursor = logging.readLogs(null).cursor();
        for (int index = 0; index < 1005; index++) logger("server.logs.test").info("line {}", index);
        LogInitializer.LogPage page = logging.readLogs(cursor);
        assertThat(page.entries()).hasSize(1000);
        assertThat(page.gap()).isTrue();
        assertThat(page.entries().getFirst().text()).contains("line 5");

        String large = "x".repeat(600_000);
        logger("server.logs.test").info(large);
        logger("server.logs.test").info(large);
        page = logging.readLogs(page.cursor());
        assertThat(page.entries()).hasSize(1);
        assertThat(page.entries().getFirst().text()).contains(large);
        assertThat(page.gap()).isTrue();
        cursor = page.cursor();
        logger("server.logs.test").info("x".repeat(2_000_000));
        logger("server.logs.test").info("after oversized entry");
        page = logging.readLogs(cursor);
        assertThat(page.gap()).isTrue();
        assertThat(page.entries()).singleElement().satisfies(entry ->
                assertThat(entry.text()).contains("after oversized entry"));
    }

    @Test
    void detectsRestartAndRejectsMalformedCursors() {
        LogInitializer previous = new LogInitializer();
        String oldCursor = previous.readLogs(null).cursor();
        LogInitializer current = new LogInitializer();
        logger("server.logs.test").info("new process");
        assertThat(current.readLogs(oldCursor).gap()).isTrue();
        assertThat(current.readLogs(oldCursor).entries()).hasSize(1);
        for (String cursor : List.of("", "invalid", oldCursor + ":1", "invalid:1",
                oldCursor.substring(0, oldCursor.indexOf(':') + 1) + "-1")) {
            assertThatThrownBy(() -> current.readLogs(cursor)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void capturesConcurrentWritersWithoutLosingEntriesAndReturnsIndependentSnapshots() throws Exception {
        LogInitializer logging = new LogInitializer();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        @SuppressWarnings("unchecked")
        ConsoleAppender<ILoggingEvent> console = (ConsoleAppender<ILoggingEvent>)
                logger(org.slf4j.Logger.ROOT_LOGGER_NAME).getAppender("CONSOLE");
        console.setOutputStream(output);
        LogInitializer.LogPage initial = logging.readLogs(null);
        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            List<Future<?>> writers = new ArrayList<>();
            for (int writer = 0; writer < 4; writer++) {
                writers.add(executor.submit(() -> {
                    for (int index = 0; index < 100; index++) logger("server.logs.test").info("concurrent {}", index);
                }));
            }
            for (Future<?> writer : writers) writer.get();
        }
        assertThat(initial.entries()).isEmpty();
        LogInitializer.LogPage page = logging.readLogs(initial.cursor());
        assertThat(page.entries()).hasSize(400);
        assertThat(page.gap()).isFalse();
        assertThat(logging.readLogs(page.cursor()).entries()).isEmpty();
        StringBuilder combined = new StringBuilder();
        for (LogInitializer.LogEntry entry : page.entries()) combined.append(entry.text());
        assertThat(combined.toString()).isEqualTo(output.toString(StandardCharsets.UTF_8));
    }

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
        assertThat(logger("org.apache.sshd.common.io.DefaultIoServiceFactoryFactory").getLevel())
                .isEqualTo(Level.WARN);
        assertThat(logger(org.slf4j.Logger.ROOT_LOGGER_NAME).getLevel()).isEqualTo(Level.ERROR);
    }

    private Logger logger(String name) {
        return context.getLogger(name);
    }

    private void silenceConsole() {
        @SuppressWarnings("unchecked")
        ConsoleAppender<ILoggingEvent> console = (ConsoleAppender<ILoggingEvent>)
                logger(org.slf4j.Logger.ROOT_LOGGER_NAME).getAppender("CONSOLE");
        console.setOutputStream(OutputStream.nullOutputStream());
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
            return;
        }
        System.setProperty(name, value);
    }
}
