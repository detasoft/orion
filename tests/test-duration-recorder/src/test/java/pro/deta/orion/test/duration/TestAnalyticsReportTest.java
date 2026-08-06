package pro.deta.orion.test.duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TestAnalyticsReportTest {
    @Test
    void writesSummaryCsvFilesAndEmptyFlameGraphs(@TempDir Path temp) throws Exception {
        Files.writeString(temp.resolve("test-durations.jsonl"), testDurationLine("A#fast", "SUCCESSFUL", 12, "")
                + testDurationLine("A#slow", "FAILED", 34, "boom"), StandardCharsets.UTF_8);

        TestAnalyticsReport.ReportFiles files = TestAnalyticsReport.generate(temp, 10);

        String summary = Files.readString(files.summary());
        String html = Files.readString(files.html());
        assertTrue(summary.contains("Tests: 2"));
        assertTrue(summary.contains("Failed: 1"));
        assertTrue(summary.contains("Byte Array Allocation Hotspots"));
        assertTrue(html.contains("table class=\"sortable\""));
        assertTrue(html.contains("A#fast"));
        assertTrue(html.contains("compareCells"));
        assertTrue(Files.isRegularFile(files.testsCsv()));
        assertTrue(Files.isRegularFile(files.modulesCsv()));
        assertTrue(Files.isRegularFile(files.allocationsCsv()));
        assertTrue(Files.isRegularFile(files.byteArrayAllocationsCsv()));
        assertTrue(Files.readString(files.cpuFlameGraph()).contains("No stack samples"));
    }

    @Test
    void writesFlameGraphFromCollapsedStacks(@TempDir Path temp) throws Exception {
        Path output = temp.resolve("flamegraph.svg");

        TestAnalyticsReport.writeFlameGraph(output, "Allocations", "bytes", Map.of(
                "root;parser;byteArray", 128L,
                "root;writer;byteArray", 64L
        ));

        String svg = Files.readString(output);
        assertTrue(svg.contains("Allocations"));
        assertTrue(svg.contains("parser"));
        assertTrue(svg.contains("writer"));
    }

    private static String testDurationLine(String testId, String status, long durationMillis, String reason) {
        return "{"
                + "\"runId\":\"run-1\","
                + "\"module\":\"core/git-parser\","
                + "\"testId\":\"" + testId + "\","
                + "\"className\":\"A\","
                + "\"methodName\":\"" + testId.substring(testId.indexOf('#') + 1) + "\","
                + "\"displayName\":\"" + testId + "\","
                + "\"status\":\"" + status + "\","
                + "\"durationMillis\":" + durationMillis + ","
                + "\"startedAt\":\"2026-08-06T00:00:00Z\","
                + "\"finishedAt\":\"2026-08-06T00:00:00Z\","
                + "\"reason\":\"" + reason + "\""
                + "}\n";
    }
}
