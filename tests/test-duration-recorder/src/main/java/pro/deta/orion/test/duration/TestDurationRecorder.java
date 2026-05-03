package pro.deta.orion.test.duration;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class TestDurationRecorder implements TestExecutionListener {
    private static final String ENABLED_PROPERTY = "orion.testDurations.enabled";
    private static final String OUTPUT_PROPERTY = "orion.testDurations.output";
    private static final String RUN_ID_PROPERTY = "orion.testDurations.runId";
    private static final String DEFAULT_RUN_ID = Instant.now().toString() + "-" + processId();

    private final ConcurrentMap<String, StartedTest> startedTests = new ConcurrentHashMap<>();
    private final List<String> records = new ArrayList<>();

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        records.clear();
        startedTests.clear();
    }

    @Override
    public void executionStarted(TestIdentifier testIdentifier) {
        if (!isEnabled() || !testIdentifier.isTest()) {
            return;
        }

        startedTests.put(testIdentifier.getUniqueId(), new StartedTest(System.nanoTime(), Instant.now()));
    }

    @Override
    public void executionSkipped(TestIdentifier testIdentifier, String reason) {
        if (!isEnabled() || !testIdentifier.isTest()) {
            return;
        }

        synchronized (records) {
            records.add(toJson(testIdentifier, "SKIPPED", 0, Instant.now(), Instant.now(), reason));
        }
    }

    @Override
    public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
        if (!isEnabled() || !testIdentifier.isTest()) {
            return;
        }

        Instant finishedAt = Instant.now();
        StartedTest started = startedTests.remove(testIdentifier.getUniqueId());
        long durationMillis = 0;
        Instant startedAt = finishedAt;
        if (started != null) {
            durationMillis = Math.max(0, (System.nanoTime() - started.nanoTime()) / 1_000_000);
            startedAt = started.instant();
        }

        String status = testExecutionResult.getStatus().name();
        String reason = testExecutionResult.getThrowable().map(Throwable::toString).orElse("");
        synchronized (records) {
            records.add(toJson(testIdentifier, status, durationMillis, startedAt, finishedAt, reason));
        }
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        if (!isEnabled()) {
            return;
        }

        List<String> snapshot;
        synchronized (records) {
            if (records.isEmpty()) {
                return;
            }
            snapshot = List.copyOf(records);
        }

        Path output = outputPath();
        try {
            Files.createDirectories(output.getParent());
            writeLocked(output, String.join(System.lineSeparator(), snapshot) + System.lineSeparator());
            System.out.printf("[orion-test-durations] recorded %d test durations to %s%n",
                    snapshot.size(), output.toAbsolutePath().normalize());
        } catch (IOException e) {
            System.err.printf("[orion-test-durations] failed to write test durations to %s: %s%n",
                    output.toAbsolutePath().normalize(), e);
        }
    }

    private static void writeLocked(Path output, String payload) throws IOException {
        try (FileChannel channel = FileChannel.open(output, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND);
             FileLock ignored = channel.lock()) {
            channel.write(StandardCharsets.UTF_8.encode(payload));
        }
    }

    private static boolean isEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty(ENABLED_PROPERTY, "true"));
    }

    private static Path outputPath() {
        String configured = System.getProperty(OUTPUT_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured);
        }

        Path workingDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Optional<Path> repositoryRoot = findRepositoryRoot(workingDirectory);
        if (repositoryRoot.isPresent()) {
            return repositoryRoot.get().resolve(".test-durations").resolve("test-durations.jsonl");
        }

        return workingDirectory.resolve("target").resolve("orion-test-durations").resolve("test-durations.jsonl");
    }

    private static Optional<Path> findRepositoryRoot(Path start) {
        Path current = start;
        while (current != null) {
            Path dotGit = current.resolve(".git");
            if (Files.isDirectory(dotGit) || Files.isRegularFile(dotGit)) {
                return Optional.of(current);
            }
            current = current.getParent();
        }
        return Optional.empty();
    }

    private static String toJson(TestIdentifier testIdentifier, String status, long durationMillis,
                                 Instant startedAt, Instant finishedAt, String reason) {
        return "{"
                + "\"schemaVersion\":1,"
                + "\"runId\":\"" + json(runId()) + "\","
                + "\"module\":\"" + json(moduleName()) + "\","
                + "\"testId\":\"" + json(testId(testIdentifier)) + "\","
                + "\"className\":\"" + json(className(testIdentifier).orElse("")) + "\","
                + "\"methodName\":\"" + json(methodName(testIdentifier).orElse("")) + "\","
                + "\"legacyReportingName\":\"" + json(legacyReportingName(testIdentifier)) + "\","
                + "\"displayName\":\"" + json(testIdentifier.getDisplayName()) + "\","
                + "\"uniqueId\":\"" + json(testIdentifier.getUniqueId()) + "\","
                + "\"status\":\"" + json(status) + "\","
                + "\"durationMillis\":" + durationMillis + ","
                + "\"startedAt\":\"" + json(startedAt.toString()) + "\","
                + "\"finishedAt\":\"" + json(finishedAt.toString()) + "\","
                + "\"reason\":\"" + json(reason) + "\""
                + "}";
    }

    private static String testId(TestIdentifier testIdentifier) {
        String uniqueId = testIdentifier.getUniqueId();
        Optional<String> className = className(testIdentifier);
        Optional<String> methodName = methodName(testIdentifier);
        if (className.isPresent() && methodName.isPresent()) {
            return className.get() + "#" + methodName.get() + invocationSuffix(uniqueId);
        }

        return legacyReportingName(testIdentifier);
    }

    private static Optional<String> className(TestIdentifier testIdentifier) {
        return uniqueIdSegment(testIdentifier.getUniqueId(), "class");
    }

    private static Optional<String> methodName(TestIdentifier testIdentifier) {
        return uniqueIdSegment(testIdentifier.getUniqueId(), "method");
    }

    private static String legacyReportingName(TestIdentifier testIdentifier) {
        String legacyName = testIdentifier.getLegacyReportingName();
        return legacyName == null || legacyName.isBlank() ? testIdentifier.getUniqueId() : legacyName;
    }

    private static Optional<String> uniqueIdSegment(String uniqueId, String segmentType) {
        String prefix = "[" + segmentType + ":";
        int start = uniqueId.indexOf(prefix);
        if (start < 0) {
            return Optional.empty();
        }

        start += prefix.length();
        int end = uniqueId.indexOf(']', start);
        if (end < 0 || end <= start) {
            return Optional.empty();
        }

        return Optional.of(uniqueId.substring(start, end));
    }

    private static String invocationSuffix(String uniqueId) {
        Optional<String> invocation = uniqueIdSegment(uniqueId, "test-template-invocation");
        return invocation.map(value -> "[" + value + "]").orElse("");
    }

    private static String moduleName() {
        Path workingDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Optional<Path> repositoryRoot = findRepositoryRoot(workingDirectory);
        if (repositoryRoot.isEmpty()) {
            return workingDirectory.getFileName().toString();
        }

        Path root = repositoryRoot.get();
        if (!workingDirectory.startsWith(root)) {
            return workingDirectory.getFileName().toString();
        }

        Path relative = root.relativize(workingDirectory);
        return relative.toString().isBlank() ? "." : relative.toString();
    }

    private static String runId() {
        return System.getProperty(RUN_ID_PROPERTY, DEFAULT_RUN_ID);
    }

    private static String processId() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        int separator = name.indexOf('@');
        return separator > 0 ? name.substring(0, separator) : name;
    }

    private static String json(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private record StartedTest(long nanoTime, Instant instant) {
    }
}
