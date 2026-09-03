package pro.deta.orion.provisioning;

import pro.deta.orion.lifecycle.state.TestOnly;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

public final class RemoteAgentdProvisioner {
    private final SshEndpoint endpoint;
    private final SshCredentials credentials;
    private final ProvisioningOptions options;
    private final String installRoot;
    private final RuntimeBundleCatalog catalog;

    public RemoteAgentdProvisioner(
            SshEndpoint endpoint,
            SshCredentials credentials,
            ProvisioningOptions options,
            String installRoot,
            RuntimeBundleCatalog catalog) {
        if (endpoint == null || credentials == null || options == null || catalog == null) {
            throw new IllegalArgumentException("Remote AgentD provisioner arguments must not be null");
        }
        if (installRoot == null || !installRoot.startsWith("/") || containsControl(installRoot)) {
            throw new IllegalArgumentException("Remote AgentD install root must be an absolute path");
        }
        this.endpoint = endpoint;
        this.credentials = credentials;
        this.options = options;
        this.installRoot = stripTrailingSlash(installRoot);
        this.catalog = catalog;
    }

    @TestOnly
    ProvisioningResult install(AgentdLaunchRequest request) throws ProvisioningException {
        if (request == null) {
            throw new IllegalArgumentException("AgentD launch request must not be null");
        }
        try (MinaSshOperation operation = MinaSshOperation.open(endpoint, credentials, options)) {
            return install(operation, request);
        }
    }

    public ProvisioningResult provision(
            AgentdLaunchRequest request,
            ProvisioningLaunchPermit permit) throws ProvisioningException {
        if (request == null || permit == null) {
            throw new IllegalArgumentException("AgentD provisioning arguments must not be null");
        }
        byte[] permitBytes = permit.copyBytes();
        byte[] channelInput = Arrays.copyOf(permitBytes, permitBytes.length + 1);
        channelInput[channelInput.length - 1] = '\n';
        Arrays.fill(permitBytes, (byte) 0);
        try (MinaSshOperation operation = MinaSshOperation.open(endpoint, credentials, options)) {
            ProvisioningResult result = installRelease(operation, request);
            launch(operation, result, request, channelInput);
            switchCurrent(operation, result.version(), request);
            return result;
        } finally {
            Arrays.fill(channelInput, (byte) 0);
        }
    }

    private ProvisioningResult install(
            MinaSshOperation operation,
            AgentdLaunchRequest request) throws ProvisioningException {
        ProvisioningResult result = installRelease(operation, request);
        switchCurrent(operation, result.version(), request);
        return result;
    }

    private ProvisioningResult installRelease(
            MinaSshOperation operation,
            AgentdLaunchRequest request) throws ProvisioningException {
        RemotePlatform platform = probePlatform(operation);
        RemoteRuntimeBundle bundle;
        try {
            bundle = catalog.select(platform, request.agentVersion());
        } catch (IllegalArgumentException error) {
            throw new ProvisioningException(
                    ProvisioningFailure.RUNTIME_UNAVAILABLE,
                    "Requested AgentD runtime bundle is unavailable",
                    error);
        }
        String releases = installRoot + "/releases";
        String staging = releases + "/.staging-" + request.launchId().value();
        String release = releases + "/" + bundle.version();
        prepareStaging(operation, staging);
        upload(operation, bundle.agentd(), staging);
        upload(operation, bundle.sessionHost(), staging);
        verifyArtifact(operation, platform, bundle.agentd(), staging);
        verifyArtifact(operation, platform, bundle.sessionHost(), staging);
        publishRelease(operation, bundle, staging, release);
        return new ProvisioningResult(platform, bundle.version(), release);
    }

    private RemotePlatform probePlatform(MinaSshOperation operation) throws ProvisioningException {
        RemoteCommandResult result = requireSuccess(
                operation,
                "printf '%s\\n' \"$(uname -s)\" \"$(uname -m)\"",
                ProvisioningFailure.REMOTE_PLATFORM,
                "Remote platform detection failed");
        List<String> lines = result.stdoutText().lines().toList();
        if (lines.size() != 2) {
            throw new ProvisioningException(
                    ProvisioningFailure.REMOTE_PLATFORM, "Remote platform response is invalid");
        }
        try {
            return RemotePlatform.parse(lines.get(0), lines.get(1));
        } catch (IllegalArgumentException error) {
            throw new ProvisioningException(
                    ProvisioningFailure.REMOTE_PLATFORM, "Remote platform is unsupported", error);
        }
    }

    private void prepareStaging(MinaSshOperation operation, String staging) throws ProvisioningException {
        String command = "umask 077; mkdir -p " + PosixShell.quote(installRoot + "/releases")
                + " " + PosixShell.quote(installRoot + "/logs")
                + "; rm -rf -- " + PosixShell.quote(staging)
                + "; mkdir -m 700 " + PosixShell.quote(staging);
        requireSuccess(operation, command, ProvisioningFailure.TRANSFER,
                "Remote runtime staging failed");
    }

    private void upload(
            MinaSshOperation operation,
            RuntimeArtifact artifact,
            String staging) throws ProvisioningException {
        String target = staging + "/" + artifact.remoteName();
        try (InputStream input = Files.newInputStream(artifact.source())) {
            RemoteCommandResult result = operation.execute(
                    "umask 077; cat > " + PosixShell.quote(target), input);
            requireZero(result, ProvisioningFailure.TRANSFER, "Remote runtime upload failed");
        } catch (IOException error) {
            throw new ProvisioningException(
                    ProvisioningFailure.TRANSFER, "Local runtime artifact could not be read", error);
        }
    }

    private void verifyArtifact(
            MinaSshOperation operation,
            RemotePlatform platform,
            RuntimeArtifact artifact,
            String directory) throws ProvisioningException {
        String target = directory + "/" + artifact.remoteName();
        String digestCommand = switch (platform) {
            case LINUX_X86_64, LINUX_AARCH64 ->
                    "sha256sum " + PosixShell.quote(target) + " | awk '{print $1}'";
            case MACOS_X86_64, MACOS_AARCH64 ->
                    "shasum -a 256 " + PosixShell.quote(target) + " | awk '{print $1}'";
        };
        RemoteCommandResult result = requireSuccess(
                operation, digestCommand, ProvisioningFailure.INTEGRITY,
                "Remote runtime digest calculation failed");
        if (!artifact.sha256().equals(result.stdoutText().strip())) {
            throw new ProvisioningException(
                    ProvisioningFailure.INTEGRITY, "Remote runtime artifact digest does not match");
        }
    }

    private void publishRelease(
            MinaSshOperation operation,
            RemoteRuntimeBundle bundle,
            String staging,
            String release) throws ProvisioningException {
        RemoteCommandResult exists = operation.execute(
                "if [ -d " + PosixShell.quote(release) + " ]; then printf present; fi", new byte[0]);
        requireZero(exists, ProvisioningFailure.ACTIVATION, "Remote release inspection failed");
        if ("present".equals(exists.stdoutText())) {
            verifyArtifact(operation, bundle.platform(), bundle.agentd(), release);
            verifyArtifact(operation, bundle.platform(), bundle.sessionHost(), release);
            requireSuccess(operation, "rm -rf -- " + PosixShell.quote(staging),
                    ProvisioningFailure.ACTIVATION, "Remote staging cleanup failed");
        } else {
            String chmod = "chmod 700 "
                    + PosixShell.quote(staging + "/" + bundle.agentd().remoteName())
                    + " " + PosixShell.quote(staging + "/" + bundle.sessionHost().remoteName())
                    + "; mv " + PosixShell.quote(staging) + " " + PosixShell.quote(release);
            requireSuccess(operation, chmod, ProvisioningFailure.ACTIVATION,
                    "Remote runtime activation failed");
        }
    }

    private void switchCurrent(
            MinaSshOperation operation,
            String version,
            AgentdLaunchRequest request) throws ProvisioningException {
        String next = installRoot + "/current.next-" + request.launchId().value();
        String linkTarget = "releases/" + version;
        String switchCommand = "rm -f -- " + PosixShell.quote(next)
                + "; ln -s " + PosixShell.quote(linkTarget) + " " + PosixShell.quote(next)
                + "; mv -f " + PosixShell.quote(next) + " " + PosixShell.quote(installRoot + "/current");
        requireSuccess(operation, switchCommand, ProvisioningFailure.ACTIVATION,
                "Remote runtime switch failed");
    }

    private void launch(
            MinaSshOperation operation,
            ProvisioningResult result,
            AgentdLaunchRequest request,
            byte[] channelInput) throws ProvisioningException {
        String release = result.releaseDirectory();
        String log = installRoot + "/logs/" + request.launchId().value() + ".log";
        String executable = PosixShell.quote(release + "/agentd");
        String arguments = renderArguments(agentdArguments(release, request));
        String detached = switch (result.platform()) {
            case LINUX_X86_64, LINUX_AARCH64 ->
                    "printf '%s\\n' \"$permit\" | nohup setsid -f "
                            + executable + arguments + " >" + PosixShell.quote(log) + " 2>&1;";
            case MACOS_X86_64, MACOS_AARCH64 ->
                    "(trap '' HUP; printf '%s\\n' \"$permit\" | "
                            + executable + arguments + " >" + PosixShell.quote(log)
                            + " 2>&1) </dev/null >/dev/null 2>&1 &";
        };
        String command = "umask 077; mkdir -p " + PosixShell.quote(request.stateDirectory())
                + " " + PosixShell.quote(installRoot + "/logs")
                + " || exit 73; IFS= read -r permit || exit 64; " + detached
                + " status=$?; unset permit; [ \"$status\" -eq 0 ] || exit \"$status\""
                + "; printf launched";
        RemoteCommandResult launch;
        try {
            launch = operation.execute(command, channelInput);
        } catch (ProvisioningException error) {
            if (error.failure() == ProvisioningFailure.TIMEOUT) {
                throw error;
            }
            throw new ProvisioningException(ProvisioningFailure.LAUNCH, "Detached AgentD launch failed", error);
        }
        if (launch.exitCode() != 0 || !"launched".equals(launch.stdoutText())) {
            throw new ProvisioningException(ProvisioningFailure.LAUNCH, "Detached AgentD launch failed");
        }
    }

    static List<String> agentdArguments(String release, AgentdLaunchRequest request) {
        return List.of(
                "--server", request.serverUri().toASCIIString(),
                "--state-dir", request.stateDirectory(),
                "--agent-id", request.agentId().value(),
                "--generation", Long.toString(request.generation().value()),
                "--launch-id", request.launchId().value().toString(),
                "--max-frame-bytes", Integer.toString(request.maxFrameBytes()),
                "--agent-version", request.agentVersion(),
                "--session-host", release + "/session-host");
    }

    private static String renderArguments(List<String> arguments) {
        StringBuilder rendered = new StringBuilder();
        for (String argument : arguments) {
            rendered.append(' ').append(PosixShell.quote(argument));
        }
        return rendered.toString();
    }

    private static RemoteCommandResult requireSuccess(
            MinaSshOperation operation,
            String command,
            ProvisioningFailure failure,
            String message) throws ProvisioningException {
        RemoteCommandResult result;
        try {
            result = operation.execute(command, new byte[0]);
        } catch (ProvisioningException error) {
            if (error.failure() == ProvisioningFailure.TIMEOUT) {
                throw error;
            }
            throw new ProvisioningException(failure, message, error);
        }
        requireZero(result, failure, message);
        return result;
    }

    private static void requireZero(
            RemoteCommandResult result,
            ProvisioningFailure failure,
            String message) throws ProvisioningException {
        if (result.exitCode() != 0) {
            throw new ProvisioningException(failure, message);
        }
    }

    private static boolean containsControl(String value) {
        return value.indexOf('\0') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }

    private static String stripTrailingSlash(String value) {
        int end = value.length();
        while (end > 1 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }
}
