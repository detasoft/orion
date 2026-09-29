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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

class TestDurationRecorderTest {
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
}
