package pro.deta.orion.test.duration;

import com.sun.management.HotSpotDiagnosticMXBean;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class TestDurationRecorder implements TestExecutionListener {
    static final String ENABLED_PROPERTY = "orion.testDurations.enabled";
    static final String OUTPUT_PROPERTY = "orion.testDurations.output";
    static final String RUN_ID_PROPERTY = "orion.testDurations.runId";
    static final String DIAGNOSTIC_THRESHOLD_PROPERTY = "orion.testDiagnostics.thresholdMillis";
    private static final String DEFAULT_RUN_ID = Instant.now().toString() + "-" + processId();

    private final ConcurrentMap<String, StartedTest> startedTests = new ConcurrentHashMap<>();
    private final List<String> records = new ArrayList<>();
    private ScheduledExecutorService watchdog;

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        records.clear();
        startedTests.clear();
        if (isEnabled()) {
            long thresholdMillis = Math.max(1, Long.getLong(DIAGNOSTIC_THRESHOLD_PROPERTY, 30_000L));
            watchdog = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "orion-test-watchdog");
                thread.setDaemon(true);
                return thread;
            });
            watchdog.scheduleAtFixedRate(() -> diagnoseSlowTests(thresholdMillis),
                    thresholdMillis, thresholdMillis, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void executionStarted(TestIdentifier testIdentifier) {
        if (!isEnabled() || !testIdentifier.isTest()) {
            return;
        }

        TestDurationJfrEvent event = new TestDurationJfrEvent();
        event.begin();
        StartedTest started = new StartedTest(System.nanoTime(), Instant.now(), event);
        startedTests.put(testIdentifier.getUniqueId(), started);
    }

    @Override
    public void executionSkipped(TestIdentifier testIdentifier, String reason) {
        if (!isEnabled() || !testIdentifier.isTest()) {
            return;
        }

        synchronized (records) {
            records.add(toJson(testIdentifier, "SKIPPED", 0, Instant.now(), Instant.now(), reason));
        }
        recordJfrEvent(new TestDurationJfrEvent(), testIdentifier, "SKIPPED", 0, reason, false);
    }

    @Override
    public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
        if (!isEnabled() || !testIdentifier.isTest()) {
            return;
        }

        if (testExecutionResult.getStatus() == TestExecutionResult.Status.FAILED) {
            writeDiagnostics("Failed test: " + testId(testIdentifier));
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
        TestDurationJfrEvent event = started == null ? new TestDurationJfrEvent() : started.event();
        recordJfrEvent(event, testIdentifier, status, durationMillis, reason, started != null);
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        if (watchdog != null) {
            watchdog.shutdownNow();
            watchdog = null;
        }
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
            if (output.getParent() != null) {
                Files.createDirectories(output.getParent());
            }
            writeLocked(output, String.join(System.lineSeparator(), snapshot) + System.lineSeparator());
            System.out.printf("[orion-test-durations] recorded %d test durations to %s%n",
                    snapshot.size(), output.toAbsolutePath().normalize());
        } catch (IOException e) {
            System.err.printf("[orion-test-durations] failed to write test durations to %s: %s%n",
                    output.toAbsolutePath().normalize(), e);
        }
    }

    private void diagnoseSlowTests(long thresholdMillis) {
        long now = System.nanoTime();
        for (StartedTest started : startedTests.values()) {
            if (now - started.nanoTime() >= TimeUnit.MILLISECONDS.toNanos(thresholdMillis)) {
                writeDiagnostics("Test exceeded " + thresholdMillis + " ms");
                return;
            }
        }
    }

    private void writeDiagnostics(String reason) {
        Path directory = outputPath().toAbsolutePath().resolveSibling("test-diagnostics");
        Path dump = directory.resolve("threads-" + processId() + "-" + UUID.randomUUID() + ".txt");
        try {
            Files.createDirectories(directory);
            ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
                    .dumpThreads(dump.toString(), HotSpotDiagnosticMXBean.ThreadDumpFormat.TEXT_PLAIN);
            StringBuilder context = new StringBuilder("\nReason: ").append(reason)
                    .append("\nModule: ").append(moduleName()).append("\nActive tests:\n");
            long now = System.nanoTime();
            for (Map.Entry<String, StartedTest> entry : startedTests.entrySet()) {
                StartedTest started = entry.getValue();
                context.append(entry.getKey()).append(" started=").append(started.instant())
                        .append(" elapsedMillis=")
                        .append(TimeUnit.NANOSECONDS.toMillis(now - started.nanoTime())).append('\n');
            }
            context.append("Child processes:\n");
            for (ProcessHandle process : ProcessHandle.current().descendants().toList()) {
                ProcessHandle.Info info = process.info();
                context.append("pid=").append(process.pid()).append(" parent=")
                        .append(process.parent().map(ProcessHandle::pid).orElse(-1L))
                        .append(" command=").append(info.command().orElse("unknown"))
                        .append(" started=").append(info.startInstant().orElse(null))
                        .append(" cpu=").append(info.totalCpuDuration().orElse(null)).append('\n');
            }
            Files.writeString(dump, context, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            System.err.printf("[orion-test-diagnostics] %s: %s%n", reason, dump);
        } catch (IOException | RuntimeException failure) {
            System.err.printf("[orion-test-diagnostics] cannot write %s: %s%n", dump, failure);
        }
    }

    private static void writeLocked(Path output, String payload) throws IOException {
        try (FileChannel channel = FileChannel.open(output, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND);
             FileLock ignored = channel.lock()) {
            channel.write(StandardCharsets.UTF_8.encode(payload));
        }
    }

    private static void recordJfrEvent(TestDurationJfrEvent event, TestIdentifier testIdentifier, String status,
                                       long durationMillis, String reason, boolean alreadyStarted) {
        if (!event.isEnabled()) {
            return;
        }

        if (!alreadyStarted) {
            event.begin();
        }
        event.runId = runId();
        event.module = moduleName();
        event.testId = testId(testIdentifier);
        event.className = className(testIdentifier).orElse("");
        event.methodName = methodName(testIdentifier).orElse("");
        event.displayName = testIdentifier.getDisplayName();
        event.status = status;
        event.durationMillis = durationMillis;
        event.reason = reason;
        event.end();
        if (event.shouldCommit()) {
            event.commit();
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

        return workingDirectory.resolve("target")
                .resolve("orion-test-durations")
                .resolve("test-durations.jsonl");
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
        return uniqueIdSegment(testIdentifier.getUniqueId(), "method")
                .or(() -> uniqueIdSegment(testIdentifier.getUniqueId(), "test-template"));
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

    private record StartedTest(long nanoTime, Instant instant, TestDurationJfrEvent event) {
    }
}
