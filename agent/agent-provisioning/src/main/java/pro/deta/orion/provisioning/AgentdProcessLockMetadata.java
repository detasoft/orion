package pro.deta.orion.provisioning;

import pro.deta.orion.agent.protocol.AgentGeneration;
import pro.deta.orion.agent.protocol.AgentLaunchId;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

record AgentdProcessLockMetadata(
        long pid,
        long startEpochMillis,
        AgentLaunchId launchId,
        AgentGeneration generation,
        String executable
) {
    private static final Set<String> FIELDS = Set.of(
            "version", "pid", "startEpochMillis", "launchId", "generation", "executable");

    static AgentdProcessLockMetadata parse(String value) {
        if (value == null || value.length() > 16 * 1024) {
            throw new IllegalArgumentException("AgentD lock metadata exceeds its size bound");
        }
        Map<String, String> fields = new HashMap<>();
        for (String line : value.split("\n")) {
            int separator = line.indexOf('=');
            if (separator <= 0) {
                throw new IllegalArgumentException("AgentD lock metadata line is invalid");
            }
            String name = line.substring(0, separator);
            String field = line.substring(separator + 1);
            if (!FIELDS.contains(name) || field.isEmpty() || fields.putIfAbsent(name, field) != null) {
                throw new IllegalArgumentException("AgentD lock metadata fields are invalid");
            }
        }
        if (!fields.keySet().equals(FIELDS) || !"2".equals(fields.get("version"))) {
            throw new IllegalArgumentException("AgentD lock metadata version is invalid");
        }
        try {
            return new AgentdProcessLockMetadata(
                    Long.parseLong(fields.get("pid")), Long.parseLong(fields.get("startEpochMillis")),
                    new AgentLaunchId(UUID.fromString(fields.get("launchId"))),
                    new AgentGeneration(Long.parseLong(fields.get("generation"))), fields.get("executable"));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("AgentD lock metadata values are invalid", error);
        }
    }
}
