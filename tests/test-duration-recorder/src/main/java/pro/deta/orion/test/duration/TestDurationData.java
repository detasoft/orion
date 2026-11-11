package pro.deta.orion.test.duration;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Reads the recorder's JSONL format for the query tools and the HTML/JFR report. */
final class TestDurationData {
    static final int MAX_FILE_BYTES = 8 * 1024 * 1024;
    private TestDurationData() {}

    static List<TestRecord> readAll(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return List.of();
        if (Files.size(path) > MAX_FILE_BYTES) {
            throw new IOException("Report input exceeds 8 MiB; select a per-run file or use the bounded query goals");
        }
        List<TestRecord> records = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) records.add(parse(line));
            }
        }
        return records;
    }

    static TestRecord parse(String json) {
        return new TestRecord(
                jsonString(json, "runId"),
                jsonString(json, "module"),
                jsonString(json, "testId"),
                jsonString(json, "className"),
                jsonString(json, "methodName"),
                jsonString(json, "displayName"),
                jsonString(json, "status"),
                jsonLong(json, "durationMillis"),
                jsonString(json, "startedAt"),
                jsonString(json, "finishedAt"),
                jsonString(json, "reason"),
                jsonLong(json, "processId"),
                jsonLong(json, "threadId"),
                jsonString(json, "threadName")
        );
    }

    private static String jsonString(String json, String key) {
        int offset = jsonValueOffset(json, key);
        if (offset < 0 || offset >= json.length() || json.charAt(offset) != '"') {
            return "";
        }

        StringBuilder value = new StringBuilder();
        for (int i = offset + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') {
                return value.toString();
            }
            if (c != '\\' || i + 1 >= json.length()) {
                value.append(c);
                continue;
            }

            char escaped = json.charAt(++i);
            switch (escaped) {
                case '"' -> value.append('"');
                case '\\' -> value.append('\\');
                case '/' -> value.append('/');
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'u' -> {
                    if (i + 4 < json.length()) {
                        value.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                }
                default -> value.append(escaped);
            }
        }
        return value.toString();
    }

    private static long jsonLong(String json, String key) {
        int offset = jsonValueOffset(json, key);
        if (offset < 0) {
            return 0;
        }

        int end = offset;
        while (end < json.length() && "-0123456789".indexOf(json.charAt(end)) >= 0) {
            end++;
        }
        if (end == offset) {
            return 0;
        }
        return Long.parseLong(json.substring(offset, end));
    }

    private static int jsonValueOffset(String json, String key) {
        String marker = "\"" + key + "\":";
        int offset = json.indexOf(marker);
        if (offset < 0) {
            return -1;
        }
        offset += marker.length();
        while (offset < json.length() && Character.isWhitespace(json.charAt(offset))) {
            offset++;
        }
        return offset;
    }

    record TestRecord(String runId, String module, String testId, String className, String methodName,
                              String displayName, String status, long durationMillis, String startedAt,
                              String finishedAt, String reason, long processId, long threadId, String threadName) {
    }

}
