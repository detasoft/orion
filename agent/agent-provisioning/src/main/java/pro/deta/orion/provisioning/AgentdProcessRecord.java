package pro.deta.orion.provisioning;

import pro.deta.orion.agent.protocol.AgentGeneration;
import pro.deta.orion.agent.protocol.AgentLaunchId;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class AgentdProcessRecord {
    static final int MAX_BYTES = 16 * 1024;
    private static final Set<String> FIELDS = Set.of(
            "version", "pid", "startEpochMillis", "nativeStartToken", "launchId", "generation",
            "releaseBase64", "executableBase64");

    private AgentdProcessRecord() {
    }

    static String serialize(AgentdProcessIdentity identity) {
        return "version=1\n"
                + "pid=" + identity.pid() + "\n"
                + "startEpochMillis=" + identity.startEpochMillis() + "\n"
                + "nativeStartToken=" + identity.nativeStartToken() + "\n"
                + "launchId=" + identity.launchId().value() + "\n"
                + "generation=" + identity.generation().value() + "\n"
                + "releaseBase64=" + encode(identity.releaseDirectory()) + "\n"
                + "executableBase64=" + encode(identity.executable()) + "\n";
    }

    static AgentdProcessIdentity parse(String value) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw malformed("record exceeds its size bound");
        }
        Map<String, String> fields = new HashMap<>();
        String[] lines = value.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line.isEmpty() && index == lines.length - 1) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator <= 0 || hasControl(line)) {
                throw malformed("record line is invalid");
            }
            String name = line.substring(0, separator);
            String field = line.substring(separator + 1);
            if (!FIELDS.contains(name) || field.isEmpty() || fields.putIfAbsent(name, field) != null) {
                throw malformed("record fields are invalid");
            }
        }
        if (!fields.keySet().equals(FIELDS) || !"1".equals(fields.get("version"))) {
            throw malformed("record fields or version are invalid");
        }
        try {
            return new AgentdProcessIdentity(
                    Long.parseLong(fields.get("pid")), Long.parseLong(fields.get("startEpochMillis")),
                    fields.get("nativeStartToken"), decode(fields.get("releaseBase64")),
                    decode(fields.get("executableBase64")),
                    new AgentLaunchId(UUID.fromString(fields.get("launchId"))),
                    new AgentGeneration(Long.parseLong(fields.get("generation"))));
        } catch (IllegalArgumentException error) {
            throw malformed("record values are invalid", error);
        }
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        byte[] decoded = Base64.getUrlDecoder().decode(value);
        String text = new String(decoded, StandardCharsets.UTF_8);
        if (!encode(text).equals(value)) {
            throw malformed("path encoding is not canonical");
        }
        return text;
    }

    private static boolean hasControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character > 0x7e) {
                return true;
            }
        }
        return false;
    }

    private static IllegalArgumentException malformed(String message) {
        return new IllegalArgumentException("Malformed AgentD process record: " + message);
    }

    private static IllegalArgumentException malformed(String message, Throwable cause) {
        return new IllegalArgumentException("Malformed AgentD process record: " + message, cause);
    }
}
