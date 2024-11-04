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
    private final List<String> categoryLevels = new ArrayList<>();
    private final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

    public LogInitializer() {
        categoryLevels.add(":INFO");
        categoryLevels.add("org.apache.sshd.server.channel.PipeDataReceiver:WARN");
        categoryLevels.add("org.apache.sshd.server.session:WARN");
        categoryLevels.add("pro.deta.orion.git.util.GitUtils:TRACE");
        categoryLevels.add("org.eclipse.jetty:WARN");
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
}
