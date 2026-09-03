package pro.deta.orion.agentd.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

class JsonSessionManifestReaderTest {
    private final JsonSessionManifestReader reader = new JsonSessionManifestReader();

    @TempDir
    Path temporaryDirectory;

    @Test
    void readsManifestWithoutJournalOrLifecycleState() throws Exception {
        Path session = writeManifest("session-1", """
                ,
                  "journalId": "legacy-id",
                  "activeSegment": 4,
                  "oldestAvailableTimestamp": 10,
                  "latestTimestamp": 20,
                  "state": "running",
                  "operationSequence": 999,
                  "futureField": {"nested": true}
                """);

        SessionManifest manifest = reader.read(session);

        assertThat(manifest.sessionId()).isEqualTo("session-1");
        assertThat(manifest.hostPid()).isEqualTo(4242);
        assertThat(manifest.childPid()).hasValue(4243);
        assertThat(manifest.control().transport()).isEqualTo(ControlEndpoint.Transport.UNIX_DOMAIN_SOCKET);
        assertThat(manifest.control().address()).isEqualTo(session.resolve("control.sock"));
        assertThat(SessionManifest.class.getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("journalId", "activeSegment", "oldestAvailableTimestamp", "latestTimestamp",
                        "state", "operationSequence");
    }

    @Test
    void readsFutureManifestAfterRemovedFieldsDisappear() throws Exception {
        Path session = writeManifest("future-session", "");

        SessionManifest manifest = reader.read(session);

        assertThat(manifest.sessionId()).isEqualTo("future-session");
        assertThat(manifest.command()).containsExactly("bash", "-l");
        assertThat(manifest.currentColumns()).isEqualTo(100);
        assertThat(manifest.sandbox().readOnlyPaths()).containsExactly("/usr");
    }

    @Test
    void readsGranularLandlockPolicyWhileLegacyFieldsRemainCompatible() throws Exception {
        Path granular = writeManifest("granular", "");
        replace(granular, "\"readOnlyPaths\": [\"/usr\"]", """
                "readOnlyPaths": ["/usr"],
                    "policyVersion": 1,
                    "handledRights": 131071,
                    "rules": [
                      {"path": "/bin", "rights": ["execute", "read-file"]},
                      {"path": "/workspace", "rights": ["read-file", "write-file", "truncate"]}
                    ]
                """);

        SessionManifest.Sandbox sandbox = reader.read(granular).sandbox();

        assertThat(sandbox.policyVersion()).hasValue(1);
        assertThat(sandbox.handledRights()).hasValue(131071);
        assertThat(sandbox.rules()).containsExactly(
                new SessionManifest.SandboxRule("/bin", java.util.List.of("execute", "read-file")),
                new SessionManifest.SandboxRule(
                        "/workspace", java.util.List.of("read-file", "write-file", "truncate")));

        assertThat(reader.read(writeManifest("legacy", "")).sandbox().policyVersion()).isEmpty();
    }

    @Test
    void rejectsMismatchedIdentityAndUnsafeUnixEndpoint() throws Exception {
        Path mismatched = writeManifest("directory-id", "", "metadata-id", "control.sock");
        Path unsafe = writeManifest("unsafe", "", "unsafe", "../other/control.sock");

        assertThatIOException().isThrownBy(() -> reader.read(mismatched))
                .withMessageContaining("does not match");
        assertThatIOException().isThrownBy(() -> reader.read(unsafe))
                .withMessageContaining("endpoint");
    }

    @Test
    void rejectsDuplicateFieldsWrongTypesAndNumericOverflow() throws Exception {
        Path duplicate = writeManifest("duplicate", ", \"hostPid\": 9999");
        Path wrongType = writeManifest("wrong-type", "");
        Files.writeString(wrongType.resolve("metadata"), Files.readString(wrongType.resolve("metadata"))
                .replace("\"currentRows\": 30", "\"currentRows\": \"30\""));
        Path overflow = writeManifest("overflow", "");
        Files.writeString(overflow.resolve("metadata"), Files.readString(overflow.resolve("metadata"))
                .replace("\"hostPid\": 4242", "\"hostPid\": 18446744073709551615"));

        assertThatIOException().isThrownBy(() -> reader.read(duplicate)).withMessageContaining("Duplicate");
        assertThatIOException().isThrownBy(() -> reader.read(wrongType)).withMessageContaining("currentRows");
        assertThatIOException().isThrownBy(() -> reader.read(overflow)).withMessageContaining("hostPid");
    }

    @Test
    void enforcesManifestSpecificDimensionTerminalAndTimeBounds() throws Exception {
        Path dimensions = writeManifest("dimensions", "");
        replace(dimensions, "\"currentRows\": 30", "\"currentRows\": 65536");
        Path terminal = writeManifest("terminal", "");
        replace(terminal, "\"term\": \"xterm-256color\"", "\"term\": \"" + "x".repeat(129) + "\"");
        Path times = writeManifest("times", "");
        replace(times, "\"sessionStartEpochMillis\": 1750000000123",
                "\"sessionStartEpochMillis\": 1749999999999");

        assertThatIOException().isThrownBy(() -> reader.read(dimensions)).withMessageContaining("currentRows");
        assertThatIOException().isThrownBy(() -> reader.read(terminal)).withMessageContaining("term");
        assertThatIOException().isThrownBy(() -> reader.read(times)).withMessageContaining("sessionStart");
    }

    @Test
    void boundsBytesConsumedFromTheOpenedManifest() throws Exception {
        Path session = writeManifest("growing", "");
        byte[] replacementBytes = new byte[1024 * 1024 + 1];
        JsonSessionManifestReader replacingReader = new JsonSessionManifestReader(
                ignored -> new ByteArrayInputStream(replacementBytes));

        assertThatIOException().isThrownBy(() -> replacingReader.read(session))
                .withMessageContaining("maximum size");
    }

    @Test
    void boundsTerminalTypeByUtf8Bytes() throws Exception {
        Path exact = writeManifest("exact-term", "");
        replace(exact, "\"term\": \"xterm-256color\"", "\"term\": \"" + "é".repeat(64) + "\"");
        Path oversized = writeManifest("oversized-term", "");
        replace(oversized, "\"term\": \"xterm-256color\"", "\"term\": \"" + "é".repeat(65) + "\"");

        assertThat(reader.read(exact).terminalType()).hasSize(64);
        assertThatIOException().isThrownBy(() -> reader.read(oversized)).withMessageContaining("term");
    }

    private Path writeManifest(String sessionId, String extraFields) throws Exception {
        return writeManifest(sessionId, extraFields, sessionId, "control.sock");
    }

    private Path writeManifest(String directoryName, String extraFields, String sessionId, String endpoint)
            throws Exception {
        Path directory = Files.createDirectories(temporaryDirectory.resolve(directoryName));
        Files.writeString(directory.resolve("metadata"), """
                {
                  "metadataVersion": 1,
                  "journalFormatVersion": 1,
                  "controlProtocolVersion": 1,
                  "sessionId": "%s",
                  "createdAtEpochMillis": 1750000000000,
                  "sessionStartEpochMillis": 1750000000123,
                  "command": ["bash", "-l"],
                  "cwd": "/work/project",
                  "hostPid": 4242,
                  "childPid": 4243,
                  "initialCols": 80,
                  "initialRows": 24,
                  "currentCols": 100,
                  "currentRows": 30,
                  "term": "xterm-256color",
                  "sandbox": {
                    "requested": true,
                    "enforcement": "landlock",
                    "unavailablePolicy": "fail",
                    "readWritePaths": ["/work/project"],
                    "readOnlyPaths": ["/usr"]
                  },
                  "control": {
                    "transport": "unix-domain-socket",
                    "endpoint": "%s"
                  }%s
                }
                """.formatted(sessionId, endpoint, extraFields));
        return directory;
    }

    private static void replace(Path directory, String target, String replacement) throws Exception {
        Path metadata = directory.resolve("metadata");
        Files.writeString(metadata, Files.readString(metadata).replace(target, replacement));
    }
}
