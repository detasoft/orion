package pro.deta.orion.test.duration;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod;

class TestDurationRecorderTest {
    @Test
    void separatesDefaultRunFilesAndRecordsTheStartingThread(@TempDir Path temp) throws Exception {
        String oldDirectory = System.getProperty("user.dir");
        String oldOutput = System.getProperty(TestDurationRecorder.OUTPUT_PROPERTY);
        String oldRun = System.getProperty(TestDurationRecorder.RUN_ID_PROPERTY);
        String oldEnabled = System.getProperty(TestDurationRecorder.ENABLED_PROPERTY);
        Files.createDirectory(temp.resolve(".git"));
        try {
            System.setProperty("user.dir", temp.toString());
            System.clearProperty(TestDurationRecorder.OUTPUT_PROPERTY);
            System.setProperty(TestDurationRecorder.ENABLED_PROPERTY, "true");
            for (String run : List.of("first/run", "second/run")) {
                System.setProperty(TestDurationRecorder.RUN_ID_PROPERTY, run);
                executeSampleTests();
                Path output = temp.resolve(".test-durations/runs")
                        .resolve(java.net.URLEncoder.encode(run, java.nio.charset.StandardCharsets.UTF_8) + ".jsonl");
                List<TestDurationData.TestRecord> records = TestDurationData.readAll(output);
                assertEquals(4, records.size());
                for (TestDurationData.TestRecord record : records) {
                    assertEquals(run, record.runId());
                    assertTrue(record.processId() > 0);
                    if (!record.status().equals("SKIPPED")) {
                        assertEquals(Thread.currentThread().threadId(), record.threadId());
                        assertEquals(Thread.currentThread().getName(), record.threadName());
                    }
                }
            }
            assertTrue(Files.notExists(temp.resolve(".test-durations/test-durations.jsonl")));
        } finally {
            restoreProperty("user.dir", oldDirectory);
            restoreProperty(TestDurationRecorder.OUTPUT_PROPERTY, oldOutput);
            restoreProperty(TestDurationRecorder.RUN_ID_PROPERTY, oldRun);
            restoreProperty(TestDurationRecorder.ENABLED_PROPERTY, oldEnabled);
        }
    }

    @Test
    void savesThreadDumpsWhileATestIsBlockedAndWhenATestFails(@TempDir Path temp) throws Exception {
        String originalEnabled = System.getProperty(TestDurationRecorder.ENABLED_PROPERTY);
        String originalOutput = System.getProperty(TestDurationRecorder.OUTPUT_PROPERTY);
        String originalThreshold = System.getProperty(TestDurationRecorder.DIAGNOSTIC_THRESHOLD_PROPERTY);
        DiagnosticSamples.entered = new CountDownLatch(1);
        DiagnosticSamples.release = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            System.setProperty(TestDurationRecorder.ENABLED_PROPERTY, "true");
            System.setProperty(TestDurationRecorder.OUTPUT_PROPERTY, temp.resolve("durations.jsonl").toString());
            System.setProperty(TestDurationRecorder.DIAGNOSTIC_THRESHOLD_PROPERTY, "25");
            Launcher launcher = LauncherFactory.create(LauncherConfig.builder()
                    .enableTestExecutionListenerAutoRegistration(false).build());
            SummaryGeneratingListener summary = new SummaryGeneratingListener();
            launcher.registerTestExecutionListeners(new TestDurationRecorder(), summary);
            Future<?> execution = executor.submit(() -> launcher.execute(LauncherDiscoveryRequestBuilder.request()
                    .selectors(selectMethod(DiagnosticSamples.class, "blockedFixture")).build()));
            try {
                assertTrue(DiagnosticSamples.entered.await(5, TimeUnit.SECONDS));
                Path directory = temp.resolve("test-diagnostics");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                Path dump;
                while ((dump = diagnosticContaining(directory, "Test exceeded")) == null
                        && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                assertTrue(dump != null, "The blocked test must be diagnosed before it finishes");
                String content = Files.readString(dump);
                assertTrue(content.contains("blockedFixture"));
                assertTrue(content.contains("elapsedMillis="));
            } finally {
                DiagnosticSamples.release.countDown();
            }
            execution.get(5, TimeUnit.SECONDS);
            assertEquals(1, summary.getSummary().getTestsSucceededCount());
            launcher.execute(LauncherDiscoveryRequestBuilder.request()
                    .selectors(selectMethod(DiagnosticSamples.class, "failedFixture")).build());
            assertEquals(1, summary.getSummary().getTestsFailedCount());
            assertTrue(diagnosticContaining(temp.resolve("test-diagnostics"), "Failed test:") != null);
        } finally {
            DiagnosticSamples.release.countDown();
            restoreProperty(TestDurationRecorder.ENABLED_PROPERTY, originalEnabled);
            restoreProperty(TestDurationRecorder.OUTPUT_PROPERTY, originalOutput);
            restoreProperty(TestDurationRecorder.DIAGNOSTIC_THRESHOLD_PROPERTY, originalThreshold);
        }
    }

    private static Path diagnosticContaining(Path directory, String marker) throws IOException {
        if (!Files.isDirectory(directory)) return null;
        try (java.nio.file.DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.txt")) {
            for (Path file : files) {
                if (Files.readString(file).contains(marker)) return file;
            }
        }
        return null;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recordsJsonDurationsAndJfrEvents(boolean basename, @TempDir Path temp) throws Exception {
        Path durations = basename ? Path.of("recorder-" + UUID.randomUUID() + ".jsonl")
                : temp.resolve("nested/test-durations.jsonl");
        String originalEnabled = System.getProperty(TestDurationRecorder.ENABLED_PROPERTY);
        String originalOutput = System.getProperty(TestDurationRecorder.OUTPUT_PROPERTY);
        String originalRunId = System.getProperty(TestDurationRecorder.RUN_ID_PROPERTY);

        try {
            System.setProperty(TestDurationRecorder.ENABLED_PROPERTY, "true");
            System.setProperty(TestDurationRecorder.OUTPUT_PROPERTY, durations.toString());
            System.setProperty(TestDurationRecorder.RUN_ID_PROPERTY, "recorder-test-run");

            Path recordingFile = temp.resolve("tests.jfr");
            try (Recording recording = new Recording()) {
                recording.enable(TestDurationJfrEvent.NAME);
                recording.start();
                executeSampleTests();
                recording.stop();
                recording.dump(recordingFile);
            }

            List<String> jsonLines = Files.readAllLines(durations);
            assertEquals(4, jsonLines.size());
            assertTrue(jsonLines.stream().anyMatch(line -> line.contains("\"status\":\"SUCCESSFUL\"")));
            assertTrue(jsonLines.stream().anyMatch(line -> line.contains("\"status\":\"SKIPPED\"")));
            assertTrue(jsonLines.stream().allMatch(line -> line.contains("\"runId\":\"recorder-test-run\"")));

            List<RecordedEvent> events = readOrionTestEvents(recordingFile);
            assertEquals(4, events.size());
            assertTrue(events.stream().anyMatch(event -> hasStatus(event, "SUCCESSFUL")));
            assertTrue(events.stream().anyMatch(event -> hasStatus(event, "SKIPPED")));
            assertTrue(events.stream().allMatch(event -> "recorder-test-run".equals(event.getString("runId"))));
            for (String invocation : List.of("successfulTest()", "skippedTest()",
                    "parameterizedTest(int)[#1]", "parameterizedTest(int)[#2]")) {
                String method = invocation.substring(0, invocation.indexOf(')') + 1);
                String testId = SampleTests.class.getName() + "#" + invocation;
                assertTrue(jsonLines.stream().anyMatch(line -> line.contains("\"testId\":\"" + testId + "\"")
                        && line.contains("\"methodName\":\"" + method + "\"")
                        && line.contains("\"className\":\"" + SampleTests.class.getName() + "\"")), testId);
                assertTrue(events.stream().anyMatch(event -> testId.equals(event.getString("testId"))
                        && method.equals(event.getString("methodName"))
                        && SampleTests.class.getName().equals(event.getString("className"))), testId);
            }
        } finally {
            restoreProperty(TestDurationRecorder.ENABLED_PROPERTY, originalEnabled);
            restoreProperty(TestDurationRecorder.OUTPUT_PROPERTY, originalOutput);
            restoreProperty(TestDurationRecorder.RUN_ID_PROPERTY, originalRunId);
            Files.deleteIfExists(durations);
        }
    }

    private static void executeSampleTests() {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(SampleTests.class))
                .build();
        Launcher launcher = LauncherFactory.create();
        launcher.execute(request);
    }

    private static List<RecordedEvent> readOrionTestEvents(Path recordingFile) throws IOException {
        List<RecordedEvent> events = new ArrayList<>();
        try (RecordingFile file = new RecordingFile(recordingFile)) {
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                if (TestDurationJfrEvent.NAME.equals(event.getEventType().getName())) {
                    events.add(event);
                }
            }
        }
        return events;
    }

    private static boolean hasStatus(RecordedEvent event, String status) {
        return status.equals(event.getString("status"));
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
            return;
        }
        System.setProperty(name, value);
    }

    static class SampleTests {
        @ParameterizedTest
        @ValueSource(ints = {1, 2})
        void parameterizedTest(int value) {
            assertTrue(value > 0);
        }

        @Test
        void successfulTest() {
        }

        @Test
        @Disabled("sample skip")
        void skippedTest() {
        }
    }

    static class DiagnosticSamples {
        private static CountDownLatch entered;
        private static CountDownLatch release;

        @Test
        void blockedFixture() throws InterruptedException {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
        }

        @Test
        void failedFixture() {
            throw new IllegalStateException("intentional diagnostic fixture failure");
        }
    }
}
