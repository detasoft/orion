package pro.deta.orion.test.duration;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

class TestDurationRecorderTest {
    @Test
    void recordsJsonDurationsAndJfrEvents(@TempDir Path temp) throws Exception {
        Path durations = temp.resolve("test-durations.jsonl");
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
            assertEquals(2, jsonLines.size());
            assertTrue(jsonLines.stream().anyMatch(line -> line.contains("\"status\":\"SUCCESSFUL\"")));
            assertTrue(jsonLines.stream().anyMatch(line -> line.contains("\"status\":\"SKIPPED\"")));
            assertTrue(jsonLines.stream().allMatch(line -> line.contains("\"runId\":\"recorder-test-run\"")));

            List<RecordedEvent> events = readOrionTestEvents(recordingFile);
            assertEquals(2, events.size());
            assertTrue(events.stream().anyMatch(event -> hasStatus(event, "SUCCESSFUL")));
            assertTrue(events.stream().anyMatch(event -> hasStatus(event, "SKIPPED")));
            assertTrue(events.stream().allMatch(event -> "recorder-test-run".equals(event.getString("runId"))));
        } finally {
            restoreProperty(TestDurationRecorder.ENABLED_PROPERTY, originalEnabled);
            restoreProperty(TestDurationRecorder.OUTPUT_PROPERTY, originalOutput);
            restoreProperty(TestDurationRecorder.RUN_ID_PROPERTY, originalRunId);
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
        @Test
        void successfulTest() {
        }

        @Test
        @Disabled("sample skip")
        void skippedTest() {
        }
    }
}
