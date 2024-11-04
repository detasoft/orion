package pro.deta.orion.test.util;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.OutputStreamAppender;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Slf4j
public final class ResourceUtils {
    private static final String JAR = "jar";
    public static final String ROOT = ".root";

    private ResourceUtils() {
    }

    public static void setTraceFor(String... categories) {
        enableLogging(Level.TRACE, categories);
    }

    public static void setErrorFor(String... categories) {
        enableLogging(Level.ERROR, categories);
    }

    private static void enableLogging(Level level, String[] categories) {
        LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
        for (String category : categories) {
            lc.getLogger(category).setLevel(level);
        }
    }

    public static void configureDefaultLogging() {
        configureDefaultRootPattern();
        setTraceFor(
                "org.apache.sshd.server.channel.PipeDataReceiver",
                "pro.deta.orion.git.util.GitUtils",
                "pro.deta.orion.util.stream.AssertiveIOClient");
        setErrorFor(
                "org.eclipse.jgit.transport",
                "org.eclipse.jgit.internal");
    }

    private static void configureDefaultRootPattern() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        PatternLayout.defaultConverterMap.put("abbrevClass", AbbreviatedClassNameConverter.class.getName());

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%d{HH:mm:ss.SSS} %level{3} [%thread{10}] %abbrevClass - %msg%n");
        encoder.start();

        Logger rootLogger = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        List<Appender<ILoggingEvent>> appenders = new ArrayList<>();
        for (Iterator<Appender<ILoggingEvent>> it = rootLogger.iteratorForAppenders(); it.hasNext(); ) {
            Appender<ILoggingEvent> a = it.next();
            if (a instanceof OutputStreamAppender<ILoggingEvent>) {
                ((OutputStreamAppender<ILoggingEvent>) a).setEncoder(encoder);
            }
        }
    }

    public static Path markerPathOf(String marker) {
        try {
            URI uri = getTestURI(formatMarker(marker));
            if (uri != null) {
                if (JAR.equalsIgnoreCase(uri.getScheme())) {
                    throw new IllegalStateException("Test resources in JAR are not supported: " + uri);
                } else {
                    Path path = Paths.get(uri);
                    return path.getParent();
                }
            }

            Path current = Paths.get("").toAbsolutePath();
            int i = 5;
            while (i-- > 0) {
                Path p = current.resolve(marker).resolve("target");
                if (p.toFile().exists())
                    return p;
                if (current.resolve(ROOT).toFile().exists())
                    break;
                current = current.getParent();
            }
            throw new FileNotFoundException("Marker '." + marker + "' not found in " + uri + ".");
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to find '." + marker + "'", e);
        }
    }

    private static String formatMarker(String marker) {
        return marker + ".resource";
    }

    public static URI getTestURI(String resourceLocation) {
        URL url = getURL(resourceLocation);
        if (url != null) {
            try {
                return url.toURI();
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("Resource '" + resourceLocation + "' not found.", e);
            }
        }
        return null;
    }

    public static URL getURL(String resourceLocation) {
        return ResourceUtils.class.getClassLoader().getResource(resourceLocation);
    }
}
