package pro.deta.orion.util;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.sift.MDCBasedDiscriminator;
import ch.qos.logback.classic.sift.SiftingAppender;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.rolling.FixedWindowRollingPolicy;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy;
import ch.qos.logback.core.spi.FilterReply;
import ch.qos.logback.core.util.Duration;
import ch.qos.logback.core.util.FileSize;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CoderResult;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

/** Logback owns writing and rotation; this owner exposes bounded reads of the original text. */
public final class ScopedLogs {
    private final Path root;

    public ScopedLogs(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    void attach(LoggerContext context, Logger logger, String pattern) {
        attach(context, logger, pattern, "tasks", "taskId");
        attach(context, logger, pattern, "users", "userId");
    }

    private void attach(LoggerContext context, Logger logger, String pattern, String kind, String key) {
        SiftingAppender sift = new SiftingAppender();
        sift.setContext(context);
        sift.setName("SCOPED-" + kind);
        MDCBasedDiscriminator discriminator = new MDCBasedDiscriminator() {
            @Override
            public String getDiscriminatingValue(ILoggingEvent event) {
                return encode(super.getDiscriminatingValue(event));
            }
        };
        discriminator.setKey(key);
        discriminator.setDefaultValue("unscoped");
        discriminator.start();
        sift.setDiscriminator(discriminator);
        sift.setTimeout(Duration.buildByMinutes(1));
        sift.setMaxAppenderCount(64);
        sift.addFilter(new Filter<>() {
            @Override
            public FilterReply decide(ILoggingEvent event) {
                String value = event.getMDCPropertyMap().get(key);
                return !validId(value)
                        ? FilterReply.DENY : FilterReply.NEUTRAL;
            }
        });
        sift.setAppenderFactory((ctx, id) -> {
            Path folder = root.resolve(kind).resolve(id);
            RollingFileAppender<ILoggingEvent> file = new RollingFileAppender<>();
            file.setContext(ctx);
            file.setName(kind + "-" + id);
            file.setFile(folder.resolve("current.log").toString());
            file.setAppend(true);
            PatternLayoutEncoder encoder = new PatternLayoutEncoder();
            encoder.setContext(ctx);
            encoder.setCharset(StandardCharsets.UTF_8);
            encoder.setPattern(pattern);
            encoder.start();
            file.setEncoder(encoder);
            FixedWindowRollingPolicy rolling = new FixedWindowRollingPolicy();
            rolling.setContext(ctx);
            rolling.setParent(file);
            rolling.setFileNamePattern(folder.resolve("archive.%i.log").toString());
            rolling.setMinIndex(1);
            rolling.setMaxIndex(5);
            rolling.start();
            SizeBasedTriggeringPolicy<ILoggingEvent> trigger = new SizeBasedTriggeringPolicy<>();
            trigger.setContext(ctx);
            trigger.setMaxFileSize(FileSize.valueOf("10MB"));
            trigger.start();
            file.setRollingPolicy(rolling);
            file.setTriggeringPolicy(trigger);
            file.start();
            return file;
        });
        sift.start();
        logger.addAppender(sift);
    }

    public List<LogFile> list(String kind) throws IOException {
        Path directory = directory(kind);
        List<LogFile> result = new ArrayList<>();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (DirectoryStream<Path> buckets = Files.newDirectoryStream(directory)) {
            for (Path bucket : buckets) {
                if (!Files.isDirectory(bucket, LinkOption.NOFOLLOW_LINKS)) continue;
                String id;
                try {
                    id = new String(Base64.getUrlDecoder().decode(bucket.getFileName().toString()),
                            StandardCharsets.UTF_8);
                    if (!encode(id).equals(bucket.getFileName().toString())) continue;
                } catch (IllegalArgumentException invalid) {
                    continue;
                }
                try (DirectoryStream<Path> files = Files.newDirectoryStream(bucket, "*.log")) {
                    for (Path file : files) {
                        if (!validFile(file.getFileName().toString())
                                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class,
                                LinkOption.NOFOLLOW_LINKS);
                        result.add(new LogFile(id, file.getFileName().toString(), attrs.size(),
                                attrs.lastModifiedTime().toInstant().toString()));
                    }
                }
            }
        }
        result.sort(Comparator.comparing(LogFile::modifiedAt).reversed()
                .thenComparing(LogFile::id).thenComparing(LogFile::file));
        return List.copyOf(result);
    }

    public Page read(String kind, String id, String file, long offset, String version) throws IOException {
        Path directory = directory(kind);
        if (!validId(id) || !validFile(file) || offset < 0 || (offset > 0 && version == null)) {
            throw new IllegalArgumentException("Invalid log file selection");
        }
        Path bucket = directory.resolve(encode(id));
        Path path = bucket.resolve(file);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(bucket, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new java.nio.file.NoSuchFileException("Log file not found");
        }
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        String currentVersion = attrs.creationTime() + ":" + attrs.fileKey();
        if ((version != null && !version.equals(currentVersion)) || offset > attrs.size()) {
            throw new ChangedFileException();
        }
        try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS)) {
            channel.position(offset);
            ByteBuffer bytes = ByteBuffer.allocate(64 * 1024);
            channel.read(bytes);
            bytes.flip();
            CharBuffer text = CharBuffer.allocate(bytes.remaining());
            CoderResult decoded = StandardCharsets.UTF_8.newDecoder().decode(bytes, text, false);
            if (decoded.isError()) decoded.throwException();
            long next = offset + bytes.position();
            text.flip();
            BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!(after.creationTime() + ":" + after.fileKey()).equals(currentVersion)) {
                throw new ChangedFileException();
            }
            return new Page(text.toString(), next, next < channel.size(), currentVersion);
        }
    }

    private Path directory(String kind) {
        if (!"tasks".equals(kind) && !"users".equals(kind)) {
            throw new IllegalArgumentException("Unknown log scope");
        }
        return root.resolve(kind);
    }

    private static boolean validFile(String file) {
        return file != null && (file.equals("current.log") || file.matches("archive\\.[1-5]\\.log"));
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean validId(String id) {
        return id != null && !id.isBlank() && id.getBytes(StandardCharsets.UTF_8).length <= 180;
    }

    public record LogFile(String id, String file, long size, String modifiedAt) { }
    public record Page(String text, long nextOffset, boolean more, String version) { }
    public static final class ChangedFileException extends IOException { }
}
