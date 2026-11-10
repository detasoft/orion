package pro.deta.orion.test.duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TestRunAnalysisTest {
    @TempDir Path directory;

    @Test
    void aggregatesLastRunsByModuleClassAndMethodAndComparesMatchingGroups() throws Exception {
        Files.writeString(directory.resolve("2026-11-09T10_00_00Z.jsonl"),
                row("2026-11-09T10:00:00Z", "a", "A", "fast", 0, 10, "SUCCESSFUL", 11)
                        + row("2026-11-09T10:00:00Z", "b", "B", "onlyOld", 0, 3, "SKIPPED", 12));
        Files.writeString(directory.resolve("2026-11-09T11_00_00Z.jsonl"),
                row("2026-11-09T11:00:00Z", "a", "A", "fast", 0, 30, "FAILED", 21));
        for (String by : List.of("module", "class", "method")) {
            Map<String, Object> summary = TestRunAnalysis.analyze("summary",
                    Map.of("data", directory.toString(), "n", "2", "by", by));
            List<Map<String, Object>> groups = groups(summary);
            Map<String, Object> first = groups.getFirst();
            assertEquals(2L, first.get("samples"));
            assertEquals(20.0, first.get("meanMillis"));
            assertEquals(true, first.get("mixedOutcomes"));
            assertEquals(2, ((List<?>) first.get("perRun")).size());
        }
        Map<String, Object> comparison = TestRunAnalysis.analyze("compare", Map.of("data", directory.toString()));
        assertEquals(20.0, groups(comparison).getFirst().get("deltaMeanMillis"));
        Map<String, Object> found = TestRunAnalysis.analyze("find",
                Map.of("data", directory.toString(), "name", "fast", "status", "failed"));
        assertEquals(1L, found.get("matched"));
        assertEquals(1, ((List<?>) found.get("tests")).size());
    }

    @Test
    void measuresHalfOpenIntervalsAndReportsTestsAndProcessesAtThePeak() throws Exception {
        String run = "2026-11-09T10:00:00Z";
        Path file = directory.resolve("run.jsonl");
        Files.writeString(file, row(run, "a", "A", "first", 0, 10, "SUCCESSFUL", 11)
                + row(run, "a", "A", "second", 5, 15, "SUCCESSFUL", 11)
                + row(run, "b", "B", "third", 10, 20, "SUCCESSFUL", 12)
                + row(run, "b", "B", "skip", 5, 15, "SKIPPED", 12));
        Map<String, Object> analysis = TestRunAnalysis.analyze("concurrency", Map.of("data", file.toString()));
        Map<?, ?> runData = (Map<?, ?>) ((List<?>) analysis.get("concurrency")).getFirst();
        assertEquals(2, runData.get("peakTests"));
        assertEquals(2, runData.get("peakKnownProcesses"));
        assertEquals(1.5, runData.get("meanTests"));
        assertEquals(2, ((List<?>) runData.get("testsAtPeak")).size());
        Map<String, Object> at = TestRunAnalysis.analyze("concurrency",
                Map.of("data", file.toString(), "at", "2026-11-09T00:00:00.010Z"));
        Map<?, ?> atRun = (Map<?, ?>) ((List<?>) at.get("concurrency")).getFirst();
        List<?> tests = (List<?>) atRun.get("testsAtTime");
        assertEquals(2, tests.size());
        assertFalse(tests.toString().contains("first"));
        assertFalse(tests.toString().contains("skip"));
    }

    @Test
    void selectsTimestampedRunsIndependentlyOfFileModificationOrderAndCanReverseComparison() throws Exception {
        String old = "2026-11-09T10:00:00Z";
        String recent = "2026-11-09T11:00:00Z";
        Path first = directory.resolve(java.net.URLEncoder.encode(old,
                java.nio.charset.StandardCharsets.UTF_8) + ".jsonl");
        Path second = directory.resolve(java.net.URLEncoder.encode(recent,
                java.nio.charset.StandardCharsets.UTF_8) + ".jsonl");
        Files.writeString(first, row(old, "a", "A", "test", 0, 10, "SUCCESSFUL", 0));
        Files.writeString(second, row(recent, "a", "A", "test", 0, 30, "SUCCESSFUL", 0));
        Files.setLastModifiedTime(first, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        Files.setLastModifiedTime(second, java.nio.file.attribute.FileTime.fromMillis(0));
        assertEquals(List.of(recent), TestRunAnalysis.analyze("runs",
                Map.of("data", directory.toString())).get("runIds"));
        Map<String, Object> reverse = TestRunAnalysis.analyze("compare",
                Map.of("data", directory.toString(), "run", old, "base", recent));
        assertEquals(-20.0, groups(reverse).getFirst().get("deltaMeanMillis"));
    }

    @Test
    void oldRecordsLeaveProcessIdentityUnknownAndNeverCountSkippedIntervals() throws Exception {
        Path file = directory.resolve("old.jsonl");
        Files.writeString(file, row("run", "a", "A", "test", 0, 10, "SUCCESSFUL", 0)
                .replace(",\"processId\":0,\"threadId\":1", "")
                + row("run", "a", "A", "skipped", 0, 20, "SKIPPED", 2));
        Map<String, Object> analysis = TestRunAnalysis.analyze("concurrency", Map.of("data", file.toString()));
        Map<?, ?> run = (Map<?, ?>) ((List<?>) analysis.get("concurrency")).getFirst();
        assertEquals(1, run.get("peakTests"));
        assertEquals(0, run.get("peakKnownProcesses"));
        assertEquals(1, run.get("unknownProcessRecords"));
    }

    @Test
    void boundsLegacyReadsAndIgnoresAnIncompleteFinalLine() throws Exception {
        Path file = directory.resolve("legacy.jsonl");
        try (java.io.RandomAccessFile output = new java.io.RandomAccessFile(file.toFile(), "rw")) {
            output.setLength(32L * 1024 * 1024);
            output.seek(output.length() - 1);
            output.write('\n');
            output.write(row("latest", "a", "A", "test", 0, 1, "SUCCESSFUL", 0)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.write("{\"unfinished\":".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        Map<String, Object> analysis = TestRunAnalysis.analyze("summary", Map.of("data", file.toString()));
        assertEquals(true, analysis.get("inputTruncated"));
        assertEquals(1L, groups(analysis).getFirst().get("samples"));
        Map<?, ?> source = (Map<?, ?>) ((List<?>) analysis.get("sources")).getFirst();
        assertTrue(((Number) source.get("bytesRead")).longValue() <= 8L * 1024 * 1024 + 1);
        assertEquals(true, source.get("incompleteLastLine"));
        assertThrows(java.io.IOException.class, () -> TestDurationData.readAll(file));
    }

    @Test
    void rejectsInvalidOptionsAndUnknownRunsWithoutFallingBackToTheWholeHistory() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> TestRunAnalysis.analyze("summary",
                Map.of("data", directory.toString(), "by", "unknown")));
        assertThrows(IllegalArgumentException.class, () -> TestRunAnalysis.analyze("summary",
                Map.of("data", directory.toString(), "n", "0")));
        assertThrows(IllegalArgumentException.class, () -> TestRunAnalysis.analyze("find",
                Map.of("data", directory.toString(), "run", "missing")));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> groups(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("groups");
    }

    private static String row(String run, String module, String type, String method,
            int start, int end, String status, int pid) {
        return "{\"runId\":\"" + run + "\",\"module\":\"" + module + "\",\"testId\":\"" + type + "#" + method
                + "\",\"className\":\"" + type + "\",\"methodName\":\"" + method + "\",\"status\":\"" + status
                + "\",\"durationMillis\":" + (end - start) + ",\"startedAt\":\"2026-11-09T00:00:00."
                + String.format("%03d", start) + "Z\",\"finishedAt\":\"2026-11-09T00:00:00."
                + String.format("%03d", end) + "Z\",\"processId\":" + pid + ",\"threadId\":1}\n";
    }
}
