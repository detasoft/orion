package pro.deta.orion.provisioning;

import org.junit.jupiter.api.Test;
import pro.deta.orion.agent.protocol.AgentGeneration;
import pro.deta.orion.agent.protocol.AgentLaunchId;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteAgentdProcessControlTest {
    @Test
    void inspectionRequiresRecordLockAndLiveProcessToAgree() throws Exception {
        FakeExecutor executor = new FakeExecutor(result(AgentdProcessRecord.serialize(identity())));
        RemoteAgentdProcessControl control = control(executor);

        assertThat(control.inspect(identity())).isEqualTo(RemoteAgentdProcessControl.ProcessState.MATCHING);
        assertThat(executor.commands.getFirst()).contains("agentd.lock", "identities", "/proc/");
    }

    @Test
    void mismatchFailsClosedWithoutSignal() {
        FakeExecutor executor = new FakeExecutor(new RemoteCommandResult(
                80, new byte[0], "identity mismatch".getBytes(StandardCharsets.US_ASCII), false, false));
        RemoteAgentdProcessControl control = control(executor);

        assertThatThrownBy(() -> control.terminate(identity(), options()))
                .isInstanceOf(ProvisioningException.class)
                .extracting(error -> ((ProvisioningException) error).failure())
                .isEqualTo(ProvisioningFailure.UNCERTAIN_IDENTITY);
        assertThat(executor.commands).allMatch(command -> !command.contains("kill -KILL"));
    }

    @Test
    void changedStartTokenBeforeKillMeansTheOldProcessIsGone() throws Exception {
        FakeExecutor executor = new FakeExecutor(result("signalled\n"), result("alive\n"), failure(3, ""));
        RemoteAgentdProcessControl control = control(executor);

        control.terminate(identity(), options());

        assertThat(executor.commands).hasSize(3);
        assertThat(executor.commands.getFirst()).contains("kill -TERM \"$pid\"");
        assertThat(executor.commands.get(2)).contains("agentd.lock", "identities", "kill -KILL");
    }

    @Test
    void processInspectionFailureIsUncertainAndNamesPid() {
        RemoteAgentdProcessControl control = control(new FakeExecutor(failure(82, "permission denied")));

        assertThatThrownBy(() -> control.inspect(identity()))
                .isInstanceOf(ProvisioningException.class)
                .satisfies(error -> {
                    ProvisioningException failure = (ProvisioningException) error;
                    assertThat(failure.failure()).isEqualTo(ProvisioningFailure.UNCERTAIN_IDENTITY);
                    assertThat(failure).hasMessageContaining("PID 73").hasMessageContaining("permission denied");
                });
    }

    @Test
    void signalPermissionFailureIsTypedAndNamesPid() {
        RemoteAgentdProcessControl control = control(new FakeExecutor(failure(81, "Operation not permitted")));

        assertThatThrownBy(() -> control.terminate(identity(), options()))
                .isInstanceOf(ProvisioningException.class)
                .satisfies(error -> {
                    ProvisioningException failure = (ProvisioningException) error;
                    assertThat(failure.failure()).isEqualTo(ProvisioningFailure.SIGNAL_PRIVILEGE);
                    assertThat(failure).hasMessageContaining("PID 73")
                            .hasMessageContaining("Operation not permitted");
                });
    }

    @Test
    void terminationTimeoutIsTypedAfterTermAndKillWithoutLaunchingAnything() {
        FakeExecutor executor = new FakeExecutor(
                result("signalled"), result("alive"), result("signalled"), result("alive"));
        AtomicLong clock = new AtomicLong();
        RemoteAgentdProcessControl control = new RemoteAgentdProcessControl(
                executor, RemotePlatform.LINUX_X86_64, "/var/lib/orion/agent", "/opt/orion",
                clock::getAndIncrement, duration -> { });

        assertThatThrownBy(() -> control.terminate(identity(), options()))
                .isInstanceOf(ProvisioningException.class)
                .extracting(error -> ((ProvisioningException) error).failure())
                .isEqualTo(ProvisioningFailure.TERMINATION_TIMEOUT);
        assertThat(executor.commands).filteredOn(command -> command.contains("kill -TERM")).hasSize(1);
        assertThat(executor.commands).filteredOn(command -> command.contains("kill -KILL")).hasSize(1);
        assertThat(executor.commands).noneMatch(command -> command.contains("nohup"));
    }

    private static RemoteAgentdProcessControl control(FakeExecutor executor) {
        return new RemoteAgentdProcessControl(
                executor, RemotePlatform.LINUX_X86_64, "/var/lib/orion/agent", "/opt/orion",
                System::nanoTime, duration -> { });
    }

    private static AgentdRecoveryOptions options() {
        return new AgentdRecoveryOptions(
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofNanos(1),
                Duration.ofNanos(1), Duration.ofMillis(1), Duration.ofMillis(1), 1);
    }

    private static AgentdProcessIdentity identity() {
        return new AgentdProcessIdentity(
                73, 9_000, "123456", "/opt/orion/releases/1", "/opt/orion/releases/1/agentd",
                new AgentLaunchId(UUID.fromString("10010203-0405-0607-0809-0a0b0c0d0e0f")),
                new AgentGeneration(5));
    }

    private static RemoteCommandResult result(String stdout) {
        return new RemoteCommandResult(
                0, stdout.getBytes(StandardCharsets.US_ASCII), new byte[0], false, false);
    }

    private static RemoteCommandResult failure(int exitCode, String stderr) {
        return new RemoteCommandResult(
                exitCode, new byte[0], stderr.getBytes(StandardCharsets.US_ASCII), false, false);
    }

    private static final class FakeExecutor implements RemoteCommandExecutor {
        private final ArrayDeque<RemoteCommandResult> results = new ArrayDeque<>();
        private final List<String> commands = new ArrayList<>();

        private FakeExecutor(RemoteCommandResult... results) {
            this.results.addAll(List.of(results));
        }

        @Override
        public RemoteCommandResult execute(String command, byte[] input) {
            commands.add(command);
            return results.removeFirst();
        }
    }
}
