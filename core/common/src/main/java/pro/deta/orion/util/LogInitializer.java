package pro.deta.orion.util;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Getter
public class LogInitializer {
    public record LogEntry(String level, String text) {
    }

    public record LogPage(String cursor, boolean gap, List<LogEntry> entries) {
    }

    public LogPage readLogs(String after) {
        return recentLogs.read(after);
    }

    private static final String TEST_DEBUG_PROPERTY = "orion.test.debug";
    private static final String TEST_LOG_LEVEL_PROPERTY = "orion.test.log.level";
    private static final String TEST_LOG_CATEGORIES_PROPERTY = "orion.test.log.categories";
    private static final String DEFAULT_TEST_LOG_LEVEL = "DEBUG";
    private static final String DEFAULT_TEST_LOG_CATEGORIES = "pro.deta.orion";

    private final List<String> categoryLevels = new ArrayList<>();
    private final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    @Getter(AccessLevel.NONE)
    private final RecentLogs recentLogs = new RecentLogs();
    private ScopedLogs scopedLogs;

    public void configureScopedLogs(Path directory) {
        scopedLogs = new ScopedLogs(directory);
        reconfigure();
    }

    public LogInitializer() {
        categoryLevels.add(":INFO");
        categoryLevels.add("org.apache.sshd.common.io.DefaultIoServiceFactoryFactory:WARN");
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
        PatternLayoutEncoder encoder = new PatternLayoutEncoder() {
            @Override
            public byte[] encode(ILoggingEvent event) {
                byte[] output = super.encode(event);
                recentLogs.append(event.getLevel().toString(), new String(output, StandardCharsets.UTF_8));
                return output;
            }
        };
        encoder.setContext(context);
        encoder.setCharset(StandardCharsets.UTF_8);
        String pattern = "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} "
                + "[taskId=%X{taskId} userId=%X{userId}] -%kvp- %msg%n";
        encoder.setPattern(pattern);
        encoder.start();

        // Create console appender
        ConsoleAppender<ILoggingEvent> consoleAppender = new ConsoleAppender<>() {
            @Override
            protected synchronized void writeOut(ILoggingEvent event) throws IOException {
                super.writeOut(event);
            }
        };
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
        if (scopedLogs != null) scopedLogs.attach(context, getLogger(null), pattern);
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

    private static final class RecentLogs {
        private static final int MAX_ENTRIES = 1000;
        private static final int MAX_CHARACTERS = 1024 * 1024;
        private final UUID instance = UUID.randomUUID();
        private final ArrayDeque<SequencedLog> entries = new ArrayDeque<>();
        private long sequence;
        private long droppedThrough;
        private int characters;

        synchronized void append(String level, String text) {
            long id = ++sequence;
            if (text.length() > MAX_CHARACTERS) {
                droppedThrough = id;
                return;
            }
            entries.addLast(new SequencedLog(id, new LogEntry(level, text)));
            characters += text.length();
            while (entries.size() > MAX_ENTRIES || characters > MAX_CHARACTERS) {
                SequencedLog removed = entries.removeFirst();
                characters -= removed.entry().text().length();
                droppedThrough = Math.max(droppedThrough, removed.sequence());
            }
        }

        synchronized LogPage read(String after) {
            long since = 0;
            boolean restarted = false;
            if (after != null) {
                try {
                    String[] parts = after.split(":", -1);
                    if (parts.length != 2) throw new IllegalArgumentException();
                    UUID source = UUID.fromString(parts[0]);
                    since = Long.parseLong(parts[1]);
                    if (since < 0) throw new IllegalArgumentException();
                    restarted = !instance.equals(source);
                    if (restarted) since = 0;
                    else if (since > sequence) throw new IllegalArgumentException();
                } catch (IllegalArgumentException failure) {
                    throw new IllegalArgumentException("Invalid log cursor");
                }
            }
            List<LogEntry> result = new ArrayList<>();
            for (SequencedLog entry : entries) {
                if (entry.sequence() > since) result.add(entry.entry());
            }
            return new LogPage(instance + ":" + sequence, restarted || since < droppedThrough,
                    List.copyOf(result));
        }

        private record SequencedLog(long sequence, LogEntry entry) {
        }
    }
}
