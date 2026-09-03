package pro.deta.orion.agentd.session;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

class ControlHostProbeTest {
    @Test
    void reportsLiveChildOnlyFromSuccessfulHostStatus() throws Exception {
        ControlHostProbe probe = new ControlHostProbe((endpoint, command) ->
                new ControlResult.Status(status(4242, true, true, 1, 1)));

        HostObservation observation = probe.probe(Path.of("session"), manifest(4242));

        assertThat(observation).isEqualTo(HostObservation.live(ChildState.LIVE));
    }

    @Test
    void reportsExitedChildFromStatusRatherThanManifestChildPid() throws Exception {
        ControlHostProbe probe = new ControlHostProbe((endpoint, command) ->
                new ControlResult.Status(status(4242, true, false, 1, 1)));

        HostObservation observation = probe.probe(Path.of("session"), manifest(4242));

        assertThat(observation).isEqualTo(HostObservation.live(ChildState.EXITED));
    }

    @Test
    void reportsUnknownChildWhenStartingStatusHasNoChildPid() throws Exception {
        HostStatus starting = new HostStatus(
                HostStatus.State.STARTING,
                true,
                false,
                false,
                80,
                24,
                4242,
                OptionalLong.empty(),
                OptionalInt.empty(),
                OptionalInt.empty(),
                1,
                1);
        ControlHostProbe probe = new ControlHostProbe((endpoint, command) ->
                new ControlResult.Status(starting));

        HostObservation observation = probe.probe(Path.of("session"), manifest(4242));

        assertThat(observation).isEqualTo(HostObservation.live(ChildState.UNKNOWN));
    }

    @Test
    void requiresStatusToDeclareTheHostLive() throws Exception {
        ControlHostProbe probe = new ControlHostProbe((endpoint, command) ->
                new ControlResult.Status(status(4242, false, true, 1, 1)));

        HostObservation observation = probe.probe(Path.of("session"), manifest(4242));

        assertThat(observation).isEqualTo(HostObservation.unreachable());
    }

    @Test
    void treatsManifestPidAsCorrelationOnlyAndRejectsAMismatch() throws Exception {
        ControlHostProbe probe = new ControlHostProbe((endpoint, command) ->
                new ControlResult.Status(status(9999, true, true, 1, 1)));

        HostObservation observation = probe.probe(Path.of("session"), manifest(4242));

        assertThat(observation).isEqualTo(HostObservation.unreachable());
    }

    @Test
    void reportsIncompatibleStatusVersionsAsProbeFailures() {
        ControlHostProbe probe = new ControlHostProbe((endpoint, command) ->
                new ControlResult.Status(status(4242, true, true, 2, 1)));

        assertThatIOException()
                .isThrownBy(() -> probe.probe(Path.of("session"), manifest(4242)))
                .withMessageContaining("version");
    }

    @Test
    void mapsConnectionFailureToUnreachableAndFramingFailureToDegradedError() throws Exception {
        SessionManifest manifest = manifest(4242);
        ControlHostProbe unreachable = new ControlHostProbe((endpoint, command) -> new ControlResult.Failed(
                command.commandId(), ControlResult.FailureKind.CONNECTION, "refused"));
        ControlHostProbe malformed = new ControlHostProbe((endpoint, command) -> new ControlResult.Failed(
                command.commandId(), ControlResult.FailureKind.FRAMING, "bad frame"));

        assertThat(unreachable.probe(Path.of("session"), manifest))
                .isEqualTo(HostObservation.unreachable());
        assertThatIOException()
                .isThrownBy(() -> malformed.probe(Path.of("session"), manifest))
                .withMessageContaining("bad frame");
    }

    @Test
    void deadlineAwareProbePassesTheCallersDeadlineToStatus() throws Exception {
        AtomicReference<OperationDeadline> observed = new AtomicReference<>();
        ControlHostProbe probe = new ControlHostProbe(
                (endpoint, command) -> new ControlResult.Failed(
                        command.commandId(), ControlResult.FailureKind.CONNECTION, "unused"),
                (endpoint, command, deadline) -> {
                    observed.set(deadline);
                    return new ControlResult.Status(status(4242, true, true, 1, 1));
                });
        OperationDeadline deadline = OperationDeadline.after(Duration.ofSeconds(1));

        HostObservation observation = probe.probe(Path.of("session"), manifest(4242), deadline);

        assertThat(observation).isEqualTo(HostObservation.live(ChildState.LIVE));
        assertThat(observed).hasValue(deadline);
    }

    private static HostStatus status(
            long hostPid,
            boolean hostLive,
            boolean childLive,
            int journalVersion,
            int controlVersion
    ) {
        return new HostStatus(
                HostStatus.State.RUNNING,
                hostLive,
                childLive,
                false,
                80,
                24,
                hostPid,
                OptionalLong.of(4343),
                OptionalInt.empty(),
                OptionalInt.empty(),
                journalVersion,
                controlVersion);
    }

    private static SessionManifest manifest(long hostPid) {
        return new SessionManifest(
                1,
                1,
                1,
                "session-1",
                1,
                2,
                List.of("sh"),
                "/workspace",
                hostPid,
                OptionalLong.of(4343),
                80,
                24,
                80,
                24,
                "xterm-256color",
                new SessionManifest.Sandbox(false, "none", "fail", List.of(), List.of()),
                new ControlEndpoint(
                        ControlEndpoint.Transport.UNIX_DOMAIN_SOCKET,
                        "control.sock",
                        Path.of("session/control.sock")));
    }
}
