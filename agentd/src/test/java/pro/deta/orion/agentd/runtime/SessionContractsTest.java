package pro.deta.orion.agentd.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.SessionId;
import pro.deta.orion.agent.protocol.WorkspaceId;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SessionContractsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void sessionSpecTakesImmutableCopiesOfCommandsAndEnvironment() {
        List<String> command = new java.util.ArrayList<>(List.of("sh", "-l"));
        Map<String, String> environment = new HashMap<>(Map.of("ORION_TEST", "value"));

        SessionSpec spec = new SessionSpec(
                new SessionId("session-1"),
                command,
                new WorkspaceReference.ExistingDirectory(temporaryDirectory),
                environment,
                160,
                50,
                "xterm-256color",
                Optional.of("truecolor"),
                SessionSpec.Sandbox.none());

        command.add("ignored");
        environment.put("LATE", "ignored");

        assertThat(spec.command()).containsExactly("sh", "-l");
        assertThat(spec.environment()).containsExactlyEntriesOf(Map.of("ORION_TEST", "value"));
    }

    @Test
    void resolvesAnExistingDirectoryWithoutChangingItsIdentity() {
        ExistingDirectoryWorkspaceResolver resolver = new ExistingDirectoryWorkspaceResolver();

        WorkspaceResolver.Resolution resolution = resolver.resolve(
                new WorkspaceReference.ExistingDirectory(temporaryDirectory.resolve(".")));

        assertThat(resolution).isEqualTo(
                new WorkspaceResolver.Resolution.Resolved(temporaryDirectory.toAbsolutePath().normalize()));
    }

    @Test
    void rejectsAMissingExistingDirectoryAsAValue() {
        ExistingDirectoryWorkspaceResolver resolver = new ExistingDirectoryWorkspaceResolver();

        WorkspaceResolver.Resolution resolution = resolver.resolve(
                new WorkspaceReference.ExistingDirectory(temporaryDirectory.resolve("missing")));

        assertThat(resolution).isInstanceOf(WorkspaceResolver.Resolution.Failed.class);
        WorkspaceResolver.Resolution.Failed failed = (WorkspaceResolver.Resolution.Failed) resolution;
        assertThat(failed.kind()).isEqualTo(SessionLaunchResult.FailureKind.INVALID_WORKSPACE);
        assertThat(failed.detail()).contains("existing directory");
    }

    @Test
    void reportsManagedWorkspacesAsUnsupported() {
        ExistingDirectoryWorkspaceResolver resolver = new ExistingDirectoryWorkspaceResolver();
        WorkspaceReference reference = new WorkspaceReference.Managed(
                new WorkspaceId("workspace-1"), Path.of("project"));

        WorkspaceResolver.Resolution resolution = resolver.resolve(reference);

        assertThat(resolution).isInstanceOf(WorkspaceResolver.Resolution.Failed.class);
        WorkspaceResolver.Resolution.Failed failed = (WorkspaceResolver.Resolution.Failed) resolution;
        assertThat(failed.kind()).isEqualTo(SessionLaunchResult.FailureKind.UNSUPPORTED_WORKSPACE);
    }

    @Test
    void failureRetainsTypedUnsupportedEnvironmentReason() {
        SessionLaunchResult result = SessionLaunchResult.failed(
                SessionLaunchResult.FailureKind.UNSUPPORTED_ENVIRONMENT,
                "native session-host does not accept arbitrary environment entries");

        assertThat(result).isEqualTo(new SessionLaunchResult.Failed(
                SessionLaunchResult.FailureKind.UNSUPPORTED_ENVIRONMENT,
                "native session-host does not accept arbitrary environment entries"));
    }
}
