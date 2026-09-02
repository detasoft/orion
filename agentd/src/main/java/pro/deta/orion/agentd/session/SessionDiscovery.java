package pro.deta.orion.agentd.session;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class SessionDiscovery {
    private final Path sessionsDirectory;
    private final SessionManifestReader manifestReader;
    private final HostProbe hostProbe;
    private final JournalProbe journalProbe;
    private final SessionRegistry registry;

    public SessionDiscovery(
            Path sessionsDirectory,
            SessionManifestReader manifestReader,
            HostProbe hostProbe,
            JournalProbe journalProbe,
            SessionRegistry registry
    ) {
        this.sessionsDirectory = Objects.requireNonNull(sessionsDirectory, "sessionsDirectory")
                .toAbsolutePath().normalize();
        this.manifestReader = Objects.requireNonNull(manifestReader, "manifestReader");
        this.hostProbe = Objects.requireNonNull(hostProbe, "hostProbe");
        this.journalProbe = Objects.requireNonNull(journalProbe, "journalProbe");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public DiscoverySnapshot reconcile() throws IOException {
        Map<String, LocalSession> sessions = new LinkedHashMap<>();
        Map<String, DiscoveryIssue> issues = new LinkedHashMap<>();
        for (Path directory : sessionDirectories()) {
            discover(directory, sessions, issues);
        }
        DiscoverySnapshot next = new DiscoverySnapshot(sessions, issues);
        registry.replace(next);
        return next;
    }

    private List<Path> sessionDirectories() throws IOException {
        if (!Files.exists(sessionsDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<Path> directories = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(sessionsDirectory)) {
            for (Path entry : entries) {
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    directories.add(entry.toAbsolutePath().normalize());
                }
            }
        }
        directories.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return directories;
    }

    private void discover(
            Path directory,
            Map<String, LocalSession> sessions,
            Map<String, DiscoveryIssue> issues
    ) {
        String directoryName = directory.getFileName().toString();
        SessionManifest manifest;
        try {
            manifest = manifestReader.read(directory);
        } catch (NoSuchFileException error) {
            issues.put(directoryName, issue(directory, DiscoveryIssue.Kind.INCOMPLETE, error));
            return;
        } catch (IOException | RuntimeException error) {
            issues.put(directoryName, issue(directory, DiscoveryIssue.Kind.DEGRADED, error));
            return;
        }

        JournalObservation journal = JournalObservation.MISSING;
        Exception journalFailure = null;
        try {
            journal = Objects.requireNonNull(journalProbe.probe(directory), "journal observation");
        } catch (IOException | RuntimeException error) {
            journalFailure = error;
        }
        if (journal == JournalObservation.MISSING && journalFailure == null) {
            issues.put(manifest.sessionId(), new DiscoveryIssue(
                    directory, DiscoveryIssue.Kind.INCOMPLETE, "journal is not initialized"));
            return;
        }

        HostObservation host;
        try {
            host = Objects.requireNonNull(
                    hostProbe.probe(directory, manifest), "host observation");
        } catch (IOException | RuntimeException error) {
            host = HostObservation.unreachable();
            issues.put(manifest.sessionId(), issue(directory, DiscoveryIssue.Kind.DEGRADED, error));
            sessions.put(manifest.sessionId(), new LocalSession(
                    directory, manifest, host, journal, LocalSessionState.DEGRADED));
            return;
        }
        if (journalFailure != null) {
            issues.put(manifest.sessionId(), issue(
                    directory, DiscoveryIssue.Kind.DEGRADED, journalFailure));
            sessions.put(manifest.sessionId(), new LocalSession(
                    directory, manifest, host, journal, LocalSessionState.DEGRADED));
            return;
        }
        LocalSessionState state = host.status() == HostObservation.Status.LIVE
                ? LocalSessionState.LIVE
                : LocalSessionState.LOST;
        sessions.put(manifest.sessionId(), new LocalSession(directory, manifest, host, journal, state));
    }

    private static DiscoveryIssue issue(Path directory, DiscoveryIssue.Kind kind, Exception error) {
        String detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return new DiscoveryIssue(directory, kind, detail);
    }
}
