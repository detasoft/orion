package pro.deta.orion.test.duration;

import pro.deta.orion.test.duration.TestDurationData.TestRecord;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Bounded, read-only queries over flushed test records; does not start tests or scan the legacy history. */
public final class TestRunAnalysis {
    private static final long MAX_QUERY_BYTES = 64L * 1024 * 1024;
    private static final Set<String> COMMANDS = Set.of("runs", "summary", "find", "concurrency", "compare");
    private static final Set<String> OPTIONS = Set.of("data", "n", "by", "run", "base", "name", "module",
            "status", "min", "top", "at");

    private TestRunAnalysis() {}

    public static void main(String[] args) throws IOException {
        if (args.length == 0 || args[0].equals("--help")) {
            System.out.println("Commands: runs, summary, find, concurrency, compare\n"
                    + "Options: data=PATH n=1 by=module|class|method run=ID base=ID name=TEXT module=TEXT\n"
                    + "         status=FAILED min=0 top=20 at=ISO_INSTANT\n"
                    + "Reads at most 8 MiB per selected file and 64 MiB per query. Never starts tests.");
            return;
        }
        Map<String, String> options = new HashMap<>();
        for (String option : OPTIONS) {
            String value = System.getenv("ORION_TEST_ANALYSIS_" + option.toUpperCase(Locale.ROOT));
            if (value != null && !value.isBlank()) options.put(option, value);
        }
        for (int index = 1; index < args.length; index++) {
            String[] pair = args[index].split("=", 2);
            if (pair.length != 2) throw new IllegalArgumentException("Expected option=value: " + args[index]);
            options.put(pair[0], pair[1]);
        }
        System.out.println(TestDurationData.json(analyze(args[0], options)));
    }

    static Map<String, Object> analyze(String command, Map<String, String> options) throws IOException {
        if (!COMMANDS.contains(command)) throw new IllegalArgumentException("Unknown command: " + command);
        for (String key : options.keySet()) {
            if (!OPTIONS.contains(key)) throw new IllegalArgumentException("Unknown option: " + key);
        }
        int count = positive(options, "n", 1, 1000);
        int top = positive(options, "top", 20, 1000);
        String by = options.getOrDefault("by", "module");
        if (!Set.of("module", "class", "method").contains(by)) {
            throw new IllegalArgumentException("BY must be module, class or method");
        }
        long minimum = Long.parseLong(options.getOrDefault("min", "0"));
        if (minimum < 0) throw new IllegalArgumentException("MIN must be nonnegative milliseconds");
        String status = options.getOrDefault("status", "").toUpperCase(Locale.ROOT);
        if (!status.isEmpty() && !Set.of("SUCCESSFUL", "FAILED", "ABORTED", "SKIPPED").contains(status)) {
            throw new IllegalArgumentException("Unknown status: " + status);
        }
        Instant at = options.containsKey("at") ? Instant.parse(options.get("at")) : null;
        Path data = Path.of(options.getOrDefault("data", ".test-durations/runs")).toAbsolutePath().normalize();
        List<Path> files = files(data);
        String selected = options.getOrDefault("run", "");
        String baseline = options.getOrDefault("base", "");
        if (Files.isDirectory(data)) {
            if (command.equals("compare")) {
                Path current = select(files, selected, 0);
                Path old = select(files, baseline, selected.isEmpty() ? 1 : files.indexOf(current) + 1);
                files = current.equals(old) ? List.of(current) : List.of(current, old);
            } else if (!selected.isEmpty()) {
                files = List.of(select(files, selected, 0));
            } else {
                files = files.subList(0, Math.min(count, files.size()));
            }
        }
        List<TestRecord> records = new ArrayList<>();
        List<Map<String, Object>> sources = new ArrayList<>();
        long bytes = 0;
        boolean truncated = false;
        for (Path file : files) {
            long planned = Math.min(Files.size(file), TestDurationData.MAX_FILE_BYTES);
            if (bytes + planned > MAX_QUERY_BYTES) {
                throw new IllegalArgumentException("Query exceeds 64 MiB; reduce N or select RUN");
            }
            Map<String, Object> source = readTail(file, records);
            bytes += ((Number) source.get("bytesRead")).longValue();
            truncated |= (boolean) source.get("partialStart") || (boolean) source.get("incompleteLastLine");
            sources.add(source);
        }
        Map<String, Instant> starts = new HashMap<>();
        for (TestRecord record : records) {
            starts.merge(record.runId(), instant(record.startedAt()), (left, right) -> left.isBefore(right) ? left : right);
        }
        List<String> runs = new ArrayList<>(starts.keySet());
        runs.sort(Comparator.comparing((String run) -> runInstant(run, starts.get(run))).reversed()
                .thenComparing(Comparator.naturalOrder()));
        if (command.equals("compare")) {
            String current = selected.isEmpty() ? runAt(runs, 0) : requireRun(runs, selected);
            String old = baseline.isEmpty() ? runAt(runs, runs.indexOf(current) + 1) : requireRun(runs, baseline);
            if (current.equals(old)) throw new IllegalArgumentException("Choose two different runs");
            runs = List.of(current, old);
        } else if (!selected.isEmpty()) {
            runs = List.of(requireRun(runs, selected));
        } else {
            runs = runs.subList(0, Math.min(count, runs.size()));
        }
        List<TestRecord> matching = new ArrayList<>();
        String name = options.getOrDefault("name", "").toLowerCase(Locale.ROOT);
        String module = options.getOrDefault("module", "");
        for (TestRecord record : records) {
            if (runs.contains(record.runId()) && record.module().contains(module)
                    && (name.isEmpty() || (record.testId() + " " + record.displayName())
                            .toLowerCase(Locale.ROOT).contains(name))
                    && (status.isEmpty() || record.status().equals(status)) && record.durationMillis() >= minimum) {
                matching.add(record);
            }
        }
        Map<String, Object> result = map("command", command, "runIds", runs, "inputTruncated", truncated,
                "runCompletionKnown", false, "sources", sources, "matched", (long) matching.size());
        switch (command) {
            case "runs" -> {
                List<Map<String, Object>> summaries = new ArrayList<>();
                for (String run : runs) summaries.add(runSummary(run, matching));
                result.put("runs", summaries);
            }
            case "summary" -> {
                List<Map<String, Object>> summaries = new ArrayList<>();
                for (Map.Entry<String, Stats> entry : group(matching, by).entrySet()) {
                    summaries.add(entry.getValue().summary(entry.getKey()));
                }
                summaries.sort(Comparator.comparingDouble((Map<String, Object> row) ->
                        ((Number) row.get("totalMillis")).doubleValue()).reversed()
                        .thenComparing(row -> row.get("group").toString()));
                result.put("by", by);
                result.put("totalGroups", summaries.size());
                result.put("groups", limit(summaries, top));
            }
            case "find" -> {
                matching.sort(Comparator.comparingLong(TestRecord::durationMillis).reversed()
                        .thenComparing(TestRecord::testId));
                List<Map<String, Object>> tests = new ArrayList<>();
                for (TestRecord record : limit(matching, top)) tests.add(test(record));
                result.put("tests", tests);
            }
            case "concurrency" -> {
                List<Map<String, Object>> concurrency = new ArrayList<>();
                for (String run : runs) concurrency.add(concurrency(run, matching, top, at));
                result.put("concurrency", concurrency);
            }
            case "compare" -> {
                Map<String, Stats> current = group(forRun(matching, runs.get(0)), by);
                Map<String, Stats> old = group(forRun(matching, runs.get(1)), by);
                Set<String> names = new java.util.TreeSet<>(current.keySet());
                names.addAll(old.keySet());
                List<Map<String, Object>> changes = new ArrayList<>();
                for (String key : names) {
                    Stats before = old.getOrDefault(key, new Stats());
                    Stats after = current.getOrDefault(key, new Stats());
                    changes.add(map("group", key, "baselineSamples", before.count, "currentSamples", after.count,
                            "baselineMeanMillis", before.mean(), "currentMeanMillis", after.mean(),
                            "deltaMeanMillis", after.mean() - before.mean(),
                            "ratio", before.mean() == 0 ? null : after.mean() / before.mean(),
                            "baselineFailed", before.failed, "currentFailed", after.failed));
                }
                changes.sort(Comparator.comparingDouble((Map<String, Object> row) ->
                        Math.abs(((Number) row.get("deltaMeanMillis")).doubleValue())).reversed()
                        .thenComparing(row -> row.get("group").toString()));
                result.put("baseline", runs.get(1));
                result.put("current", runs.get(0));
                result.put("by", by);
                result.put("totalGroups", changes.size());
                result.put("groups", limit(changes, top));
            }
            default -> throw new AssertionError(command);
        }
        return result;
    }

    private static int positive(Map<String, String> options, String key, int fallback, int maximum) {
        int value = Integer.parseInt(options.getOrDefault(key, Integer.toString(fallback)));
        if (value < 1 || value > maximum) throw new IllegalArgumentException(key + " must be 1.." + maximum);
        return value;
    }

    private static List<Path> files(Path data) throws IOException {
        if (Files.isRegularFile(data)) return List.of(data);
        if (!Files.exists(data)) return List.of();
        List<Path> files;
        try (java.util.stream.Stream<Path> entries = Files.list(data)) {
            files = new ArrayList<>(entries.filter(path -> Files.isRegularFile(path)
                    && path.getFileName().toString().endsWith(".jsonl")).toList());
        }
        files.sort(Comparator.comparing((Path path) -> runInstant(fileId(path), modified(path))).reversed()
                .thenComparing(Path::toString));
        return files;
    }

    private static String fileId(Path path) {
        String name = path.getFileName().toString();
        return URLDecoder.decode(name.substring(0, name.length() - ".jsonl".length()), StandardCharsets.UTF_8);
    }

    private static Instant modified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private static Instant runInstant(String run, Instant fallback) {
        int end = run.indexOf('Z');
        try {
            return Instant.parse(end >= 0 ? run.substring(0, end + 1) : run);
        } catch (java.time.format.DateTimeParseException ignored) {
            return fallback;
        }
    }

    private static Path select(List<Path> files, String id, int fallback) {
        if (!id.isEmpty()) {
            for (Path file : files) if (fileId(file).equals(id)) return file;
            throw new IllegalArgumentException("Unknown run: " + id);
        }
        if (fallback >= files.size()) throw new IllegalArgumentException("Two recorded runs are required");
        return files.get(fallback);
    }

    private static String requireRun(List<String> runs, String id) {
        if (!runs.contains(id)) throw new IllegalArgumentException("Unknown or unflushed run: " + id);
        return id;
    }

    private static String runAt(List<String> runs, int index) {
        if (index >= runs.size()) throw new IllegalArgumentException("Two recorded runs are required");
        return runs.get(index);
    }

    private static Map<String, Object> readTail(Path file, List<TestRecord> records) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file.toFile(), "r")) {
            long end = input.length();
            long start = Math.max(0, end - TestDurationData.MAX_FILE_BYTES);
            input.seek(start == 0 ? 0 : start - 1);
            boolean boundary = start == 0 || input.read() == '\n';
            byte[] bytes = new byte[(int) (end - start)];
            input.readFully(bytes);
            int lineStart = 0;
            if (!boundary) {
                while (lineStart < bytes.length && bytes[lineStart] != '\n') lineStart++;
                lineStart++;
            }
            for (int index = Math.min(lineStart, bytes.length); index < bytes.length; index++) {
                if (bytes[index] != '\n') continue;
                String line = new String(bytes, lineStart, index - lineStart, StandardCharsets.UTF_8).strip();
                if (!line.isEmpty()) {
                    TestRecord record = TestDurationData.parse(line);
                    if (!line.startsWith("{") || !line.endsWith("}") || record.runId().isEmpty()
                            || record.testId().isEmpty() || record.status().isEmpty()) {
                        throw new IOException("Invalid complete record in " + file + " at byte " + (start + lineStart));
                    }
                    instant(record.startedAt());
                    instant(record.finishedAt());
                    records.add(record);
                }
                lineStart = index + 1;
            }
            return map("file", file.toString(), "fromByte", start, "toByte", end,
                    "bytesRead", (long) bytes.length + (start == 0 ? 0 : 1), "partialStart", start != 0,
                    "incompleteLastLine", bytes.length > 0 && bytes[bytes.length - 1] != '\n');
        }
    }

    private static Instant instant(String value) {
        return Instant.parse(value);
    }

    private static Map<String, Stats> group(List<TestRecord> records, String by) {
        Map<String, Stats> groups = new TreeMap<>();
        for (TestRecord record : records) {
            String key = record.module();
            if (!by.equals("module")) key += ":" + record.className();
            if (by.equals("method")) key += "#" + record.methodName();
            groups.computeIfAbsent(key, ignored -> new Stats()).add(record);
        }
        return groups;
    }

    private static List<TestRecord> forRun(List<TestRecord> records, String run) {
        List<TestRecord> found = new ArrayList<>();
        for (TestRecord record : records) if (record.runId().equals(run)) found.add(record);
        return found;
    }

    private static Map<String, Object> runSummary(String run, List<TestRecord> records) {
        Stats stats = new Stats();
        Instant start = null;
        Instant end = null;
        for (TestRecord record : forRun(records, run)) {
            stats.add(record);
            Instant begin = instant(record.startedAt());
            Instant finish = instant(record.finishedAt());
            if (start == null || begin.isBefore(start)) start = begin;
            if (end == null || finish.isAfter(end)) end = finish;
        }
        Map<String, Object> result = stats.summary(run);
        result.put("startedAt", start);
        result.put("finishedAt", end);
        result.put("wallMillis", start == null ? 0.0 : millis(start, end));
        return result;
    }

    private static Map<String, Object> concurrency(String run, List<TestRecord> all, int top, Instant at) {
        List<TestRecord> tests = forRun(all, run);
        TreeMap<Instant, List<TestRecord>> starts = new TreeMap<>();
        TreeMap<Instant, List<TestRecord>> ends = new TreeMap<>();
        int unknown = 0;
        for (TestRecord test : tests) {
            if (test.status().equals("SKIPPED")) continue;
            Instant start = instant(test.startedAt());
            Instant end = instant(test.finishedAt());
            if (end.isBefore(start)) throw new IllegalArgumentException("Negative interval: " + test.testId());
            if (start.equals(end)) continue;
            if (test.processId() == 0) unknown++;
            starts.computeIfAbsent(start, ignored -> new ArrayList<>()).add(test);
            ends.computeIfAbsent(end, ignored -> new ArrayList<>()).add(test);
        }
        Set<Instant> boundaries = new java.util.TreeSet<>(starts.keySet());
        boundaries.addAll(ends.keySet());
        List<TestRecord> active = new ArrayList<>();
        List<TestRecord> peakTests = List.of();
        List<Map<String, Object>> timeline = new ArrayList<>();
        Instant previous = null;
        Instant peakAt = null;
        int peak = 0;
        int peakProcesses = 0;
        int peakThreads = 0;
        double area = 0;
        for (Instant time : boundaries) {
            if (previous != null) {
                double duration = millis(previous, time);
                area += duration * active.size();
                timeline.add(map("startedAt", previous, "finishedAt", time, "tests", active.size(),
                        "knownProcesses", processes(active), "knownThreads", threads(active)));
            }
            active.removeAll(ends.getOrDefault(time, List.of()));
            active.addAll(starts.getOrDefault(time, List.of()));
            if (active.size() > peak) {
                peak = active.size();
                peakAt = time;
                peakTests = List.copyOf(limit(active, top));
            }
            peakProcesses = Math.max(peakProcesses, processes(active));
            peakThreads = Math.max(peakThreads, threads(active));
            previous = time;
        }
        double wall = boundaries.isEmpty() ? 0 : millis(starts.firstKey(), ends.lastKey());
        timeline.sort(Comparator.comparingInt((Map<String, Object> row) -> (int) row.get("tests")).reversed()
                .thenComparing(row -> row.get("startedAt").toString()));
        Map<String, Object> result = map("runId", run, "peakTests", peak, "peakAt", peakAt,
                "meanTests", wall == 0 ? 0.0 : area / wall, "wallMillis", wall,
                "peakKnownProcesses", peakProcesses, "peakKnownThreads", peakThreads,
                "unknownProcessRecords", unknown, "testsAtPeak", testMaps(peakTests, top),
                "totalIntervals", timeline.size(), "busiestIntervals", limit(timeline, top));
        if (at != null) {
            List<TestRecord> found = new ArrayList<>();
            for (TestRecord test : tests) {
                if (!test.status().equals("SKIPPED") && !instant(test.startedAt()).isAfter(at)
                        && instant(test.finishedAt()).isAfter(at)) found.add(test);
            }
            result.put("at", at);
            result.put("testsAtTimeCount", found.size());
            result.put("testsAtTime", testMaps(found, top));
        }
        return result;
    }

    private static int processes(List<TestRecord> tests) {
        Set<Long> pids = new HashSet<>();
        for (TestRecord test : tests) if (test.processId() > 0) pids.add(test.processId());
        return pids.size();
    }

    private static int threads(List<TestRecord> tests) {
        Set<String> threads = new HashSet<>();
        for (TestRecord test : tests) {
            if (test.processId() > 0 && test.threadId() > 0) threads.add(test.processId() + ":" + test.threadId());
        }
        return threads.size();
    }

    private static double millis(Instant start, Instant end) {
        return Duration.between(start, end).toNanos() / 1_000_000.0;
    }

    private static List<Map<String, Object>> testMaps(List<TestRecord> tests, int top) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (TestRecord test : limit(tests, top)) result.add(test(test));
        return result;
    }

    private static Map<String, Object> test(TestRecord test) {
        return map("runId", test.runId(), "module", test.module(), "testId", test.testId(),
                "className", test.className(), "methodName", test.methodName(), "status", test.status(),
                "durationMillis", test.durationMillis(), "startedAt", test.startedAt(), "finishedAt", test.finishedAt(),
                "processId", test.processId() == 0 ? null : test.processId(),
                "threadId", test.threadId() == 0 ? null : test.threadId(), "threadName", test.threadName());
    }

    private static <T> List<T> limit(List<T> items, int maximum) {
        return items.subList(0, Math.min(maximum, items.size()));
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) map.put((String) pairs[index], pairs[index + 1]);
        return map;
    }

    private static final class Stats {
        private long count;
        private long failed;
        private long successful;
        private long skipped;
        private long aborted;
        private long sum;
        private final List<Long> durations = new ArrayList<>();
        private final Map<String, Stats> runs = new TreeMap<>();

        private void add(TestRecord test) {
            addSample(test);
            runs.computeIfAbsent(test.runId(), ignored -> new Stats()).addSample(test);
        }

        private void addSample(TestRecord test) {
            count++;
            switch (test.status()) {
                case "SUCCESSFUL" -> successful++;
                case "FAILED" -> failed++;
                case "ABORTED" -> aborted++;
                case "SKIPPED" -> skipped++;
                default -> throw new IllegalArgumentException("Unknown recorded status: " + test.status());
            }
            if (!test.status().equals("SKIPPED")) {
                sum += test.durationMillis();
                durations.add(test.durationMillis());
            }
        }

        private double mean() {
            return durations.isEmpty() ? 0.0 : (double) sum / durations.size();
        }

        private Map<String, Object> summary(String name) {
            durations.sort(Comparator.naturalOrder());
            List<Map<String, Object>> perRun = new ArrayList<>();
            for (Map.Entry<String, Stats> entry : runs.entrySet()) {
                perRun.add(entry.getValue().summary(entry.getKey()));
            }
            return map("group", name, "samples", count, "successful", successful, "failed", failed,
                    "aborted", aborted, "skipped", skipped, "totalMillis", sum, "meanMillis", mean(),
                    "p95Millis", durations.isEmpty() ? 0L : durations.get((int) Math.ceil(durations.size() * .95) - 1),
                    "maxMillis", durations.isEmpty() ? 0L : durations.getLast(),
                    "mixedOutcomes", successful > 0 && failed + aborted > 0, "perRun", perRun);
        }
    }
}
