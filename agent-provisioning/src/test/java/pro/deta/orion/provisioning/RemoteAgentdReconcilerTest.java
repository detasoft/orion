package pro.deta.orion.provisioning;

import org.junit.jupiter.api.Test;
import pro.deta.orion.agent.protocol.AgentGeneration;
import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.AgentLaunchId;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteAgentdReconcilerTest {
    @Test
    void adoptsExactPublishedLaunchAndFinishesCurrentWithoutDuplicateLaunch() throws Exception {
        AgentdLaunchRequest request = request();
        AgentdProcessIdentity identity = new AgentdProcessIdentity(
                7331, 9000, "123456", "/opt/orion/releases/1", "/opt/orion/releases/1/agentd",
                request.launchId(), request.generation());
        FakeExecutor executor = new FakeExecutor(
                result(AgentdProcessRecord.serialize(identity)),
                result(AgentdProcessRecord.serialize(identity)), result("releases/old\n"), result(""));
        RemoteAgentdProvisioner provisioner = RemoteAgentdProvisioner.forCommands("/opt/orion");

        AgentdReplacementResult replacement = provisioner.adoptAndCommit(
                executor, new ProvisioningResult(RemotePlatform.LINUX_X86_64, "1",
                        "/opt/orion/releases/1"), request);

        assertThat(replacement.state()).isEqualTo(AgentdReplacementResult.State.ADOPTED);
        assertThat(executor.commands)
                .noneMatch(command -> command.contains("nohup") || command.contains("setsid"));
        assertThat(executor.commands.getLast()).contains("current.next-");
    }

    @Test
    void launchUsesPidFromProcessOwnedLockAndCommitsCurrentOnlyAfterProof() throws Exception {
        AgentdLaunchRequest request = request();
        String executable = "/opt/orion/releases/1/agentd";
        String lock = "version=2\npid=7331\nstartEpochMillis=9000\nlaunchId="
                + request.launchId().value() + "\ngeneration=7\nexecutable=" + executable + "\n";
        AgentdProcessIdentity identity = new AgentdProcessIdentity(
                7331, 9000, "123456", "/opt/orion/releases/1", executable,
                request.launchId(), request.generation());
        FakeExecutor executor = new FakeExecutor(
                result("launched"), result(lock), result("123456\n" + executable + "\n"),
                result("verified"), result("published"),
                result(AgentdProcessRecord.serialize(identity)), result(""));
        RemoteAgentdProvisioner provisioner = RemoteAgentdProvisioner.forCommands("/opt/orion");
        try (AgentdLaunchAttempt attempt = new AgentdLaunchAttempt(
                request, new ProvisioningLaunchPermit("permit".getBytes(StandardCharsets.US_ASCII)))) {
            AgentdReplacementResult replacement = provisioner.launchAndCommit(
                    executor, new ProvisioningResult(RemotePlatform.LINUX_X86_64, "1",
                            "/opt/orion/releases/1"), attempt, Duration.ofSeconds(1));

            assertThat(replacement.identity().pid()).isEqualTo(7331);
            assertThat(executor.commands.getFirst()).doesNotContain("$!");
            assertThat(executor.commands.get(executor.commands.size() - 1)).contains("current.next-");
            assertThat(executor.commands.indexOf(executor.publicationCommand))
                    .isLessThan(executor.commands.size() - 1);
            assertThat(indexContaining(executor.commands, "/fdinfo/"))
                    .isLessThan(executor.commands.indexOf(executor.publicationCommand));
            assertThat(executor.inputs).noneMatch(input -> new String(input, StandardCharsets.UTF_8)
                    .contains("permit") && input.length > "permit\n".length());
        }
    }

    @Test
    void launchRetriesTransientPartialLockWrite() throws Exception {
        AgentdLaunchRequest request = request();
        String executable = "/opt/orion/releases/1/agentd";
        String lock = "version=2\npid=7331\nstartEpochMillis=9000\nlaunchId="
                + request.launchId().value() + "\ngeneration=7\nexecutable=" + executable + "\n";
        AgentdProcessIdentity identity = new AgentdProcessIdentity(
                7331, 9000, "123456", "/opt/orion/releases/1", executable,
                request.launchId(), request.generation());
        FakeExecutor executor = new FakeExecutor(
                result("launched"), result("version=2\npid="), result(lock),
                result("123456\n" + executable + "\n"), result("verified"), result("published"),
                result(AgentdProcessRecord.serialize(identity)), result(""));
        RemoteAgentdProvisioner provisioner = RemoteAgentdProvisioner.forCommands("/opt/orion");
        try (AgentdLaunchAttempt attempt = new AgentdLaunchAttempt(
                request, new ProvisioningLaunchPermit("permit".getBytes(StandardCharsets.US_ASCII)))) {
            AgentdReplacementResult replacement = provisioner.launchAndCommit(
                    executor, new ProvisioningResult(RemotePlatform.LINUX_X86_64, "1",
                            "/opt/orion/releases/1"), attempt, Duration.ofSeconds(1));

            assertThat(replacement.state()).isEqualTo(AgentdReplacementResult.State.LAUNCHED);
            assertThat(executor.commands).filteredOn(command -> command.contains("agentd.lock"))
                    .hasSizeGreaterThanOrEqualTo(3);
        }
    }

    @Test
    void publicationFailureReprovesExactLockAndFinishesSameAttempt() throws Exception {
        AgentdLaunchRequest request = request();
        String executable = "/opt/orion/releases/1/agentd";
        String lock = "version=2\npid=7331\nstartEpochMillis=9000\nlaunchId="
                + request.launchId().value() + "\ngeneration=7\nexecutable=" + executable + "\n";
        AgentdProcessIdentity identity = new AgentdProcessIdentity(
                7331, 9000, "123456", "/opt/orion/releases/1", executable,
                request.launchId(), request.generation());
        FakeExecutor executor = new FakeExecutor(
                result("launched"), result(lock), result("123456\n" + executable + "\n"),
                result("verified"), response(73, ""), result("verified"), result("published"),
                result(AgentdProcessRecord.serialize(identity)), result(""), result(""));
        RemoteAgentdProvisioner provisioner = RemoteAgentdProvisioner.forCommands("/opt/orion");
        try (AgentdLaunchAttempt attempt = new AgentdLaunchAttempt(
                request, new ProvisioningLaunchPermit("permit".getBytes(StandardCharsets.US_ASCII)))) {
            AgentdReplacementResult replacement = provisioner.launchAndCommit(
                    executor, new ProvisioningResult(RemotePlatform.LINUX_X86_64, "1",
                            "/opt/orion/releases/1"), attempt, Duration.ofSeconds(1));

            assertThat(replacement.identity()).isEqualTo(identity);
            assertThat(executor.commands).filteredOn(command -> command.contains(".identity.next")).hasSize(2);
            assertThat(executor.commands.get(3)).contains("/fdinfo/");
            assertThat(executor.commands.get(5)).contains("/fdinfo/");
        }
    }

    @Test
    void prepublicationAdoptionRequiresKernelLockProofBeforePublishingIdentity() throws Exception {
        AgentdLaunchRequest request = request();
        String executable = "/opt/orion/releases/1/agentd";
        String lock = "version=2\npid=7331\nstartEpochMillis=9000\nlaunchId="
                + request.launchId().value() + "\ngeneration=7\nexecutable=" + executable + "\n";
        AgentdProcessIdentity identity = new AgentdProcessIdentity(
                7331, 9000, "123456", "/opt/orion/releases/1", executable,
                request.launchId(), request.generation());
        FakeExecutor executor = new FakeExecutor(
                result(lock), result("123456\n" + executable + "\n"), result("verified"),
                result("published"), result(AgentdProcessRecord.serialize(identity)), result(""), result(""));
        RemoteAgentdProvisioner provisioner = RemoteAgentdProvisioner.forCommands("/opt/orion");

        AgentdReplacementResult replacement = provisioner.adoptProcessLockAndCommit(
                executor, new ProvisioningResult(RemotePlatform.LINUX_X86_64, "1",
                        "/opt/orion/releases/1"), request, Duration.ofSeconds(1));

        assertThat(replacement.state()).isEqualTo(AgentdReplacementResult.State.ADOPTED);
        int proof = indexContaining(executor.commands, "/fdinfo/");
        int publication = executor.commands.indexOf(executor.publicationCommand);
        assertThat(proof).isLessThan(publication);
        assertThat(executor.commands).noneMatch(command -> command.contains("nohup"));
    }

    @Test
    void missingLockAfterDetachedLaunchIsUncertainAndCannotTriggerDuplicateLaunch() {
        AgentdLaunchRequest request = request();
        FakeExecutor executor = new FakeExecutor(result("launched"), response(3, ""));
        RemoteAgentdProvisioner provisioner = RemoteAgentdProvisioner.forCommands("/opt/orion");
        AgentdLaunchAttempt attempt = new AgentdLaunchAttempt(
                request, new ProvisioningLaunchPermit("permit".getBytes(StandardCharsets.US_ASCII)));

        assertThatThrownBy(() -> provisioner.launchAndCommit(
                executor, new ProvisioningResult(RemotePlatform.LINUX_X86_64, "1",
                        "/opt/orion/releases/1"), attempt, Duration.ofNanos(1)))
                .isInstanceOf(ProvisioningException.class)
                .extracting(error -> ((ProvisioningException) error).failure())
                .isEqualTo(ProvisioningFailure.UNCERTAIN_IDENTITY);
        assertThat(executor.commands).filteredOn(command -> command.contains("nohup")).hasSize(1);
        attempt.close();
    }

    @Test
    void productionPartialAdoptionRejectsChangedNativeToken() {
        AgentdLaunchRequest request = request();
        AgentdProcessIdentity expected = new AgentdProcessIdentity(
                7331, 9000, "123456", "/opt/orion/releases/1", "/opt/orion/releases/1/agentd",
                request.launchId(), request.generation());
        AgentdProcessIdentity changed = new AgentdProcessIdentity(
                expected.pid(), expected.startEpochMillis(), "654321",
                expected.releaseDirectory(), expected.executable(), expected.launchId(), expected.generation());
        AgentdReplacementResult recovered = new AgentdReplacementResult(
                AgentdReplacementResult.State.ADOPTED, changed,
                new ProvisioningResult(RemotePlatform.LINUX_X86_64, "1", changed.releaseDirectory()));

        assertThatThrownBy(() -> RemoteAgentdProvisioner.requireExactPartial(recovered, expected))
                .isInstanceOf(ProvisioningException.class)
                .extracting(error -> ((ProvisioningException) error).failure())
                .isEqualTo(ProvisioningFailure.UNCERTAIN_IDENTITY);
    }

    @Test
    void publishedPartialMismatchFailsBeforeInspectionOrActivation() {
        AgentdLaunchRequest request = request();
        AgentdProcessIdentity expected = identity(request, 7331, "123456");
        AgentdProcessIdentity changed = identity(request, 7442, "654321");
        FakeExecutor executor = new FakeExecutor(result(AgentdProcessRecord.serialize(changed)));
        RemoteAgentdProvisioner provisioner = RemoteAgentdProvisioner.forCommands("/opt/orion");

        assertThatThrownBy(() -> provisioner.adoptAndCommit(
                executor, provisioningResult(), request, expected))
                .isInstanceOf(ProvisioningException.class)
                .extracting(error -> ((ProvisioningException) error).failure())
                .isEqualTo(ProvisioningFailure.UNCERTAIN_IDENTITY);
        assertThat(executor.commands).hasSize(1);
        assertThat(executor.commands)
                .noneMatch(command -> command.contains(".identity.next")
                        || command.contains("current.next-") || command.contains("/proc/"));
    }

    @Test
    void processLockPartialMismatchFailsBeforeProofPublicationOrActivation() {
        AgentdLaunchRequest request = request();
        AgentdProcessIdentity expected = identity(request, 7331, "123456");
        AgentdProcessIdentity changed = identity(request, 7442, "654321");
        String lock = "version=2\npid=" + changed.pid() + "\nstartEpochMillis=9000\nlaunchId="
                + request.launchId().value() + "\ngeneration=7\nexecutable=" + changed.executable() + "\n";
        FakeExecutor executor = new FakeExecutor(
                result(lock), result(changed.nativeStartToken() + "\n" + changed.executable() + "\n"));
        RemoteAgentdProvisioner provisioner = RemoteAgentdProvisioner.forCommands("/opt/orion");

        assertThatThrownBy(() -> provisioner.adoptProcessLockAndCommit(
                executor, provisioningResult(), request, Duration.ofSeconds(1), expected))
                .isInstanceOf(ProvisioningException.class)
                .extracting(error -> ((ProvisioningException) error).failure())
                .isEqualTo(ProvisioningFailure.UNCERTAIN_IDENTITY);
        assertThat(executor.commands).hasSize(2);
        assertThat(executor.commands)
                .noneMatch(command -> command.contains("/fdinfo/")
                        || command.contains(".identity.next") || command.contains("current.next-"));
    }

    private static int indexContaining(List<String> commands, String text) {
        for (int index = 0; index < commands.size(); index++) {
            if (commands.get(index).contains(text)) {
                return index;
            }
        }
        return -1;
    }

    private static AgentdLaunchRequest request() {
        return new AgentdLaunchRequest(
                URI.create("https://orion.example/agent/control"), "/var/lib/orion/agent",
                new AgentId("agent-1"), new AgentGeneration(7),
                new AgentLaunchId(UUID.fromString("10010203-0405-0607-0809-0a0b0c0d0e0f")),
                1024, "1");
    }

    private static AgentdProcessIdentity identity(
            AgentdLaunchRequest request, long pid, String nativeStartToken) {
        return new AgentdProcessIdentity(
                pid, 9000, nativeStartToken, "/opt/orion/releases/1", "/opt/orion/releases/1/agentd",
                request.launchId(), request.generation());
    }

    private static ProvisioningResult provisioningResult() {
        return new ProvisioningResult(RemotePlatform.LINUX_X86_64, "1", "/opt/orion/releases/1");
    }

    private static RemoteCommandResult result(String stdout) {
        return response(0, stdout);
    }

    private static RemoteCommandResult response(int exitCode, String stdout) {
        return new RemoteCommandResult(
                exitCode, stdout.getBytes(StandardCharsets.UTF_8), new byte[0], false, false);
    }

    private static final class FakeExecutor implements RemoteCommandExecutor {
        private final ArrayDeque<RemoteCommandResult> results = new ArrayDeque<>();
        private final List<String> commands = new ArrayList<>();
        private final List<byte[]> inputs = new ArrayList<>();
        private String publicationCommand;

        private FakeExecutor(RemoteCommandResult... results) {
            this.results.addAll(List.of(results));
        }

        @Override
        public RemoteCommandResult execute(String command, byte[] input) {
            commands.add(command);
            inputs.add(input.clone());
            if (command.contains(".identity.next")) {
                publicationCommand = command;
            }
            return results.removeFirst();
        }
    }
}
