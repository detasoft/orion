package pro.deta.orion.agentd.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SessionDiscoveryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void reconstructsEmptyAndPopulatedRegistriesFromDisk() throws Exception {
        Path sessions = Files.createDirectories(temporaryDirectory.resolve("sessions"));
        SessionRegistry registry = new SessionRegistry();
        TestHostProbe hosts = new TestHostProbe();
        SessionDiscovery discovery = discovery(sessions, registry, hosts, new FileSystemJournalProbe());

        assertThat(discovery.reconcile().sessions()).isEmpty();

        createSession(sessions, "live-session", "00000001.cbor");
        hosts.put("live-session", HostObservation.live(ChildState.LIVE));
        DiscoverySnapshot populated = discovery.reconcile();

        assertThat(populated.sessions()).containsOnlyKeys("live-session");
        assertThat(populated.sessions().get("live-session").state()).isEqualTo(LocalSessionState.LIVE);
        assertThat(registry.snapshot()).isSameAs(populated);

        SessionRegistry restartedRegistry = new SessionRegistry();
        discovery(sessions, restartedRegistry, hosts, new FileSystemJournalProbe()).reconcile();
        assertThat(restartedRegistry.snapshot().sessions()).containsOnlyKeys("live-session");
    }

    @Test
    void classifiesDeadHostsAndIsolatesUnreadableJournals() throws Exception {
        Path sessions = Files.createDirectories(temporaryDirectory.resolve("sessions"));
        createSession(sessions, "lost-session", "journal-000001.seg");
        createSession(sessions, "degraded-session", "00000001.cbor");
        createSession(sessions, "healthy-session", "00000001.cbor.zst");
        TestHostProbe hosts = new TestHostProbe();
        hosts.put("lost-session", HostObservation.unreachable());
        hosts.put("degraded-session", HostObservation.live(ChildState.UNKNOWN));
        hosts.put("healthy-session", HostObservation.live(ChildState.LIVE));
        JournalProbe journals = directory -> {
            if (directory.getFileName().toString().equals("degraded-session")) {
                throw new IOException("permission denied");
            }
            return new FileSystemJournalProbe().probe(directory);
        };

        DiscoverySnapshot snapshot = discovery(sessions, new SessionRegistry(), hosts, journals).reconcile();

        assertThat(snapshot.sessions().get("lost-session").state()).isEqualTo(LocalSessionState.LOST);
        assertThat(snapshot.sessions().get("degraded-session").state()).isEqualTo(LocalSessionState.DEGRADED);
        assertThat(snapshot.sessions().get("degraded-session").host().status())
                .isEqualTo(HostObservation.Status.LIVE);
        assertThat(snapshot.sessions().get("healthy-session").state()).isEqualTo(LocalSessionState.LIVE);
        assertThat(snapshot.issues()).containsKey("degraded-session");
        assertThat(snapshot.sessions()).hasSize(3);
    }

    @Test
    void omitsIncompleteAndInvalidDirectoriesWithoutDisturbingPeers() throws Exception {
        Path sessions = Files.createDirectories(temporaryDirectory.resolve("sessions"));
        createSession(sessions, "healthy-session", "00000001.cbor");
        Files.createDirectories(sessions.resolve("concurrent-session"));
        Path invalid = Files.createDirectories(sessions.resolve("invalid-session"));
        Files.writeString(invalid.resolve("metadata"), "not-json");
        TestHostProbe hosts = new TestHostProbe();
        hosts.put("healthy-session", HostObservation.live(ChildState.LIVE));

        DiscoverySnapshot snapshot = discovery(
                sessions, new SessionRegistry(), hosts, new FileSystemJournalProbe()).reconcile();

        assertThat(snapshot.sessions()).containsOnlyKeys("healthy-session");
        assertThat(snapshot.issues().get("concurrent-session").kind()).isEqualTo(DiscoveryIssue.Kind.INCOMPLETE);
        assertThat(snapshot.issues().get("invalid-session").kind()).isEqualTo(DiscoveryIssue.Kind.DEGRADED);
    }

    @Test
    void atomicallyReplacesChangedAndRemovedSessions() throws Exception {
        Path sessions = Files.createDirectories(temporaryDirectory.resolve("sessions"));
        Path session = createSession(sessions, "replace-session", "00000001.cbor");
        TestHostProbe hosts = new TestHostProbe();
        hosts.put("replace-session", HostObservation.live(ChildState.LIVE));
        SessionRegistry registry = new SessionRegistry();
        SessionDiscovery discovery = discovery(sessions, registry, hosts, new FileSystemJournalProbe());
        DiscoverySnapshot first = discovery.reconcile();

        String changed = Files.readString(session.resolve("metadata")).replace(
                "\"currentCols\": 100", "\"currentCols\": 132");
        Path replacement = session.resolve("metadata.next");
        Files.writeString(replacement, changed);
        Files.move(replacement, session.resolve("metadata"), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        DiscoverySnapshot second = discovery.reconcile();

        assertThat(first.sessions().get("replace-session").manifest().currentColumns()).isEqualTo(100);
        assertThat(second.sessions().get("replace-session").manifest().currentColumns()).isEqualTo(132);
        assertThat(registry.snapshot()).isSameAs(second);

        Files.delete(session.resolve("metadata"));
        DiscoverySnapshot third = discovery.reconcile();
        assertThat(third.sessions()).isEmpty();
        assertThat(third.issues()).containsKey("replace-session");
    }

    private SessionDiscovery discovery(
            Path sessions, SessionRegistry registry, HostProbe hosts, JournalProbe journals) {
        return new SessionDiscovery(sessions, new JsonSessionManifestReader(), hosts, journals, registry);
    }

    private Path createSession(Path root, String sessionId, String journalName) throws Exception {
        Path directory = Files.createDirectories(root.resolve(sessionId));
        Files.writeString(directory.resolve(journalName), "journal");
        Files.writeString(directory.resolve("metadata"), manifest(sessionId));
        return directory;
    }

    private String manifest(String sessionId) {
        return """
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
                    "requested": false,
                    "enforcement": "none",
                    "unavailablePolicy": "fail",
                    "readWritePaths": [],
                    "readOnlyPaths": []
                  },
                  "control": {"transport": "unix-domain-socket", "endpoint": "control.sock"},
                  "latestTimestamp": 999,
                  "operationSequence": 123
                }
                """.formatted(sessionId);
    }

    private static final class TestHostProbe implements HostProbe {
        private final Map<String, HostObservation> observations = new HashMap<>();

        void put(String sessionId, HostObservation observation) {
            observations.put(sessionId, observation);
        }

        @Override
        public HostObservation probe(Path sessionDirectory, SessionManifest manifest) {
            return observations.getOrDefault(manifest.sessionId(), HostObservation.unreachable());
        }
    }
}
