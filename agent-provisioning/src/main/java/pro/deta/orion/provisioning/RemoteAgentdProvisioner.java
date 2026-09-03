package pro.deta.orion.provisioning;

import pro.deta.orion.lifecycle.state.TestOnly;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

public final class RemoteAgentdProvisioner {
    private final SshEndpoint endpoint;
    private final SshCredentials credentials;
    private final ProvisioningOptions options;
    private final String installRoot;
    private final RuntimeBundleCatalog catalog;

    private RemoteAgentdProvisioner(String installRoot) {
        this.endpoint = null;
        this.credentials = null;
        this.options = null;
        this.installRoot = stripTrailingSlash(installRoot);
        this.catalog = null;
    }

    @TestOnly
    static RemoteAgentdProvisioner forCommands(String installRoot) {
        if (installRoot == null || !installRoot.startsWith("/") || containsControl(installRoot)) {
            throw new IllegalArgumentException("Remote AgentD install root must be an absolute path");
        }
        return new RemoteAgentdProvisioner(installRoot);
    }

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
            switchCurrent(operation, result.version(), request, result.platform());
            return result;
        } finally {
            Arrays.fill(channelInput, (byte) 0);
        }
    }

    AgentdReplacementResult reconcile(
            AgentdLaunchAttempt attempt,
            AgentdProcessIdentity previous,
            AgentdRecoveryOptions recoveryOptions) throws ProvisioningException, InterruptedException {
        if (attempt == null || recoveryOptions == null) {
            throw new IllegalArgumentException("AgentD reconciliation arguments must not be null");
        }
        AgentdLaunchRequest request = attempt.request();
        try (MinaSshOperation operation = MinaSshOperation.open(endpoint, credentials, options)) {
            ProvisioningResult installed = installRelease(operation, request);
            requireReplacementPlatform(installed.platform());
            AgentdReplacementResult adopted = adoptAndCommit(operation, installed, request);
            if (adopted != null) {
                return adopted;
            }
            adopted = adoptProcessLockAndCommit(
                    operation, installed, request, recoveryOptions.startupTimeout());
            if (adopted != null) {
                return adopted;
            }
            if (previous != null) {
                RemoteAgentdProcessControl control = new RemoteAgentdProcessControl(
                        operation, installed.platform(), request.stateDirectory(), installRoot,
                        System::nanoTime, duration -> Thread.sleep(duration));
                control.terminate(previous, recoveryOptions);
            } else if (hasProcessLock(operation, request, installed.platform())) {
                throw new ProvisioningException(
                        ProvisioningFailure.UNCERTAIN_IDENTITY,
                        "An unrecorded AgentD process lock prevents replacement");
            }
            return launchAndCommit(operation, installed, attempt, recoveryOptions.startupTimeout());
        }
    }

    AgentdReplacementResult recoverPartial(
            AgentdLaunchAttempt attempt,
            AgentdProcessIdentity partial,
            AgentdRecoveryOptions recoveryOptions) throws ProvisioningException, InterruptedException {
        if (attempt == null || partial == null || recoveryOptions == null) {
            throw new IllegalArgumentException("AgentD partial recovery arguments must not be null");
        }
        AgentdLaunchRequest request = attempt.request();
        requirePartialRequest(partial, request);
        try (MinaSshOperation operation = MinaSshOperation.open(endpoint, credentials, options)) {
            ProvisioningResult installed = installRelease(operation, request);
            requireReplacementPlatform(installed.platform());
            AgentdReplacementResult adopted = adoptAndCommit(operation, installed, request, partial);
            if (adopted != null) {
                return requireExactPartial(adopted, partial);
            }
            adopted = adoptProcessLockAndCommit(
                    operation, installed, request, recoveryOptions.startupTimeout(), partial);
            if (adopted != null) {
                return requireExactPartial(adopted, partial);
            }
            throw new ProvisioningException(
                    ProvisioningFailure.UNCERTAIN_IDENTITY,
                    "Exact partial AgentD identity is no longer recoverable without a fresh permit");
        }
    }

    private static void requirePartialRequest(
            AgentdProcessIdentity partial, AgentdLaunchRequest request) throws ProvisioningException {
        if (!partial.launchId().equals(request.launchId())
                || !partial.generation().equals(request.generation())) {
            throw new ProvisioningException(
                    ProvisioningFailure.UNCERTAIN_IDENTITY,
                    "Partial AgentD identity does not match its launch attempt");
        }
    }

    @TestOnly
    static AgentdReplacementResult requireExactPartial(
            AgentdReplacementResult recovered,
            AgentdProcessIdentity partial) throws ProvisioningException {
        if (!recovered.identity().equals(partial)) {
            throw new ProvisioningException(
                    ProvisioningFailure.UNCERTAIN_IDENTITY,
                    "Recovered AgentD does not match the exact observed partial process identity");
        }
        return recovered;
    }

    AgentdReplacementResult adoptProcessLockAndCommit(
            RemoteCommandExecutor operation,
            ProvisioningResult result,
            AgentdLaunchRequest request,
            Duration startupTimeout) throws ProvisioningException, InterruptedException {
        return adoptProcessLockAndCommit(operation, result, request, startupTimeout, null);
    }

    AgentdReplacementResult adoptProcessLockAndCommit(
            RemoteCommandExecutor operation,
            ProvisioningResult result,
            AgentdLaunchRequest request,
            Duration startupTimeout,
            AgentdProcessIdentity requiredIdentity) throws ProvisioningException, InterruptedException {
        AgentdProcessLockMetadata lock = readProcessLock(operation, request, result.platform());
        if (lock == null) {
            return null;
        }
        String executable = result.releaseDirectory() + "/agentd";
        if (!lock.launchId().equals(request.launchId())
                || !lock.generation().equals(request.generation())
                || !lock.executable().equals(executable)) {
            return null;
        }
        AgentdProcessIdentity identity = observeNativeIdentity(operation, result, request, lock);
        requireObservedIdentity(identity, requiredIdentity);
        RemoteAgentdProcessControl control = new RemoteAgentdProcessControl(
                operation, result.platform(), request.stateDirectory(), installRoot,
                System::nanoTime, duration -> Thread.sleep(duration));
        try {
            control.proveUnpublishedIdentity(identity);
            publishIdentityWithRecovery(operation, identity, result.platform(), startupTimeout, control);
            if (control.inspect(identity) != RemoteAgentdProcessControl.ProcessState.MATCHING) {
                throw new ProvisioningException(
                        ProvisioningFailure.UNCERTAIN_IDENTITY, "Recovered AgentD launch is no longer alive");
            }
            inspectCurrent(operation);
            switchCurrent(operation, result.version(), request, result.platform());
            return new AgentdReplacementResult(AgentdReplacementResult.State.ADOPTED, identity, result);
        } catch (ProvisioningException failure) {
            throw partialIdentityFailure(identity, failure);
        }
    }

    private boolean hasProcessLock(
            RemoteCommandExecutor operation,
            AgentdLaunchRequest request,
            RemotePlatform platform) throws ProvisioningException {
        return readProcessLock(operation, request, platform) != null;
    }

    private AgentdProcessLockMetadata readProcessLock(
            RemoteCommandExecutor operation,
            AgentdLaunchRequest request,
            RemotePlatform platform) throws ProvisioningException {
        String lock = request.stateDirectory() + "/agentd.lock";
        RemoteCommandResult response = operation.execute(
                safeOwnerFileCommand(lock, "cat -- " + PosixShell.quote(lock), platform), new byte[0]);
        if (response.exitCode() == 3) {
            return null;
        }
        if (response.exitCode() != 0 || response.stdoutTruncated()) {
            throw new ProvisioningException(
                    identityReadFailure(response), "AgentD process lock is unsafe or unreadable");
        }
        try {
            return AgentdProcessLockMetadata.parse(response.stdoutText());
        } catch (IllegalArgumentException malformed) {
            throw new ProvisioningException(
                    ProvisioningFailure.MALFORMED_IDENTITY, "AgentD process lock is malformed", malformed);
        }
    }

    AgentdReplacementResult launchAndCommit(
            RemoteCommandExecutor operation,
            ProvisioningResult result,
            AgentdLaunchAttempt attempt,
            Duration startupTimeout) throws ProvisioningException, InterruptedException {
        AgentdLaunchRequest request = attempt.request();
        byte[] permitBytes = attempt.permit().copyBytes();
        byte[] channelInput = Arrays.copyOf(permitBytes, permitBytes.length + 1);
        channelInput[channelInput.length - 1] = '\n';
        Arrays.fill(permitBytes, (byte) 0);
        try {
            launch(operation, result, request, channelInput);
            AgentdProcessLockMetadata lock;
            try {
                lock = awaitProcessLock(operation, result, request, startupTimeout);
            } catch (ProvisioningException failure) {
                if (failure.failure() == ProvisioningFailure.STARTUP_TIMEOUT) {
                    throw new ProvisioningException(
                            ProvisioningFailure.UNCERTAIN_IDENTITY,
                            "Detached AgentD did not publish an exact identity before the startup deadline",
                            failure);
                }
                throw failure;
            }
            AgentdProcessIdentity identity = observeNativeIdentity(operation, result, request, lock);
            RemoteAgentdProcessControl control = new RemoteAgentdProcessControl(
                    operation, result.platform(), request.stateDirectory(), installRoot,
                    System::nanoTime, duration -> Thread.sleep(duration));
            try {
                control.proveUnpublishedIdentity(identity);
                publishIdentityWithRecovery(operation, identity, result.platform(), startupTimeout, control);
                if (control.inspect(identity) != RemoteAgentdProcessControl.ProcessState.MATCHING) {
                    throw new ProvisioningException(
                            ProvisioningFailure.UNCERTAIN_IDENTITY,
                            "Launched AgentD identity could not be proven");
                }
                switchCurrent(operation, result.version(), request, result.platform());
                return new AgentdReplacementResult(AgentdReplacementResult.State.LAUNCHED, identity, result);
            } catch (ProvisioningException failure) {
                throw partialIdentityFailure(identity, failure);
            }
        } finally {
            Arrays.fill(channelInput, (byte) 0);
        }
    }

    AgentdReplacementResult adoptAndCommit(
            RemoteCommandExecutor operation,
            ProvisioningResult result,
            AgentdLaunchRequest request) throws ProvisioningException {
        return adoptAndCommit(operation, result, request, null);
    }

    AgentdReplacementResult adoptAndCommit(
            RemoteCommandExecutor operation,
            ProvisioningResult result,
            AgentdLaunchRequest request,
            AgentdProcessIdentity requiredIdentity) throws ProvisioningException {
        String file = identityFile(request);
        RemoteCommandResult response = operation.execute(
                safeOwnerFileCommand(file, "cat -- " + PosixShell.quote(file), result.platform()), new byte[0]);
        if (response.exitCode() == 3) {
            return null;
        }
        if (response.exitCode() != 0 || response.stdoutTruncated()) {
            throw new ProvisioningException(
                    identityReadFailure(response), "AgentD launch identity is unsafe or unreadable");
        }
        AgentdProcessIdentity identity;
        try {
            identity = AgentdProcessRecord.parse(response.stdoutText());
        } catch (IllegalArgumentException malformed) {
            throw new ProvisioningException(
                    ProvisioningFailure.MALFORMED_IDENTITY, "AgentD launch identity is malformed", malformed);
        }
        requireRequestedIdentity(identity, result, request);
        requireObservedIdentity(identity, requiredIdentity);
        RemoteAgentdProcessControl control = new RemoteAgentdProcessControl(
                operation, result.platform(), request.stateDirectory(), installRoot,
                System::nanoTime, duration -> Thread.sleep(duration));
        try {
            if (control.inspect(identity) != RemoteAgentdProcessControl.ProcessState.MATCHING) {
                throw new ProvisioningException(
                        ProvisioningFailure.UNCERTAIN_IDENTITY,
                        "Recorded AgentD launch is no longer alive");
            }
            inspectCurrent(operation);
            switchCurrent(operation, result.version(), request, result.platform());
            return new AgentdReplacementResult(AgentdReplacementResult.State.ADOPTED, identity, result);
        } catch (ProvisioningException failure) {
            throw partialIdentityFailure(identity, failure);
        }
    }

    private void requireRequestedIdentity(
            AgentdProcessIdentity identity,
            ProvisioningResult result,
            AgentdLaunchRequest request) throws ProvisioningException {
        if (!identity.launchId().equals(request.launchId())
                || !identity.generation().equals(request.generation())
                || !identity.releaseDirectory().equals(result.releaseDirectory())
                || !identity.executable().equals(result.releaseDirectory() + "/agentd")) {
            throw new ProvisioningException(
                    ProvisioningFailure.UNCERTAIN_IDENTITY, "Recorded AgentD launch does not match request");
        }
    }

    private static void requireObservedIdentity(
            AgentdProcessIdentity observed,
            AgentdProcessIdentity required) throws ProvisioningException {
        if (required != null && !observed.equals(required)) {
            throw new ProvisioningException(
                    ProvisioningFailure.UNCERTAIN_IDENTITY,
                    "Observed AgentD does not match the exact required partial process identity");
        }
    }

    private void inspectCurrent(RemoteCommandExecutor operation) throws ProvisioningException {
        String current = installRoot + "/current";
        String command = "if [ ! -e " + PosixShell.quote(current) + " ] && [ ! -L "
                + PosixShell.quote(current) + " ]; then printf missing; exit 0; fi; "
                + "[ -L " + PosixShell.quote(current) + " ] || exit 79; readlink " + PosixShell.quote(current);
        RemoteCommandResult response = operation.execute(command, new byte[0]);
        if (response.exitCode() != 0 || response.stdoutTruncated()) {
            throw new ProvisioningException(
                    ProvisioningFailure.UNSAFE_IDENTITY, "Current AgentD release state is unsafe");
        }
    }

    private AgentdProcessLockMetadata awaitProcessLock(
            RemoteCommandExecutor operation,
            ProvisioningResult result,
            AgentdLaunchRequest request,
            Duration startupTimeout) throws ProvisioningException, InterruptedException {
        String lock = request.stateDirectory() + "/agentd.lock";
        long deadline = saturatingAdd(System.nanoTime(), startupTimeout.toNanos());
        boolean incompleteObserved = false;
        do {
            RemoteCommandResult response = operation.execute(
                    safeOwnerFileCommand(
                            lock, "cat -- " + PosixShell.quote(lock), result.platform()),
                    new byte[0]);
            if (response.exitCode() == 0) {
                AgentdProcessLockMetadata metadata;
                try {
                    metadata = AgentdProcessLockMetadata.parse(response.stdoutText());
                } catch (IllegalArgumentException malformed) {
                    incompleteObserved = true;
                    metadata = null;
                }
                if (metadata != null) {
                    String executable = result.releaseDirectory() + "/agentd";
                    if (metadata.launchId().equals(request.launchId())
                            && metadata.generation().equals(request.generation())
                            && metadata.executable().equals(executable)) {
                        return metadata;
                    }
                    incompleteObserved = true;
                }
            }
            if (response.exitCode() != 0 && response.exitCode() != 3) {
                throw new ProvisioningException(
                        ProvisioningFailure.UNSAFE_IDENTITY, "AgentD lock metadata is unsafe or unreadable");
            }
            if (System.nanoTime() >= deadline) {
                throw new ProvisioningException(
                        incompleteObserved ? ProvisioningFailure.UNCERTAIN_IDENTITY
                                : ProvisioningFailure.STARTUP_TIMEOUT,
                        incompleteObserved ? "AgentD process lock remained incomplete or mismatched"
                                : "AgentD did not publish its process-owned lock");
            }
            Thread.sleep(25);
        } while (true);
    }

    private AgentdProcessIdentity observeNativeIdentity(
            RemoteCommandExecutor operation,
            ProvisioningResult result,
            AgentdLaunchRequest request,
            AgentdProcessLockMetadata lock) throws ProvisioningException {
        String command = nativeProbe(result.platform(), lock.pid());
        RemoteCommandResult response = operation.execute(command, new byte[0]);
        if (response.exitCode() != 0 || response.stdoutTruncated()) {
            throw new ProvisioningException(
                    ProvisioningFailure.UNCERTAIN_IDENTITY, "Launched AgentD native identity is unavailable");
        }
        List<String> lines = response.stdoutText().lines().toList();
        if (lines.size() != 2 || !lines.get(1).equals(lock.executable())) {
            throw new ProvisioningException(
                    ProvisioningFailure.UNCERTAIN_IDENTITY, "Launched AgentD executable identity disagreed");
        }
        try {
            return new AgentdProcessIdentity(
                    lock.pid(), lock.startEpochMillis(), lines.get(0), result.releaseDirectory(),
                    lock.executable(), request.launchId(), request.generation());
        } catch (IllegalArgumentException malformed) {
            throw new ProvisioningException(
                    ProvisioningFailure.MALFORMED_IDENTITY, "Launched AgentD identity is malformed", malformed);
        }
    }

    private void publishIdentity(
            RemoteCommandExecutor operation,
            AgentdProcessIdentity identity,
            RemotePlatform platform) throws ProvisioningException {
        String directory = installRoot + "/identities";
        String name = identity.generation().value() + "-" + identity.launchId().value() + ".identity";
        String target = directory + "/" + name;
        String temporary = target + ".identity.next";
        String directoryStat = switch (platform) {
            case LINUX_X86_64, LINUX_AARCH64 -> "stat -c '%u:%a' -- " + PosixShell.quote(directory);
            case MACOS_X86_64, MACOS_AARCH64 -> "stat -f '%u:%Lp' -- " + PosixShell.quote(directory);
        };
        String command = "umask 077; if [ -e " + PosixShell.quote(directory) + " ]; then "
                + "[ -d " + PosixShell.quote(directory) + " ] && [ ! -L " + PosixShell.quote(directory)
                + " ] || exit 79; uid=$(id -u); [ \"$(" + directoryStat + ")\" = \"$uid:700\" ] || exit 79; "
                + "else mkdir -m 700 " + PosixShell.quote(directory) + " || exit 73; fi; "
                + "rm -f -- " + PosixShell.quote(temporary) + " || exit 73; "
                + "[ ! -L " + PosixShell.quote(temporary) + " ] || exit 79; "
                + "(set -C; umask 077; cat > " + PosixShell.quote(temporary) + ") && chmod 600 "
                + PosixShell.quote(temporary)
                + " && mv -f " + PosixShell.quote(temporary) + " " + PosixShell.quote(target)
                + " && printf published";
        RemoteCommandResult response = operation.execute(
                command, AgentdProcessRecord.serialize(identity).getBytes(StandardCharsets.UTF_8));
        if (response.exitCode() != 0 || !"published".equals(response.stdoutText())) {
            throw new ProvisioningException(
                    response.exitCode() == 79 ? ProvisioningFailure.UNSAFE_IDENTITY
                            : ProvisioningFailure.ACTIVATION,
                    "AgentD process identity publication failed");
        }
    }

    private void publishIdentityWithRecovery(
            RemoteCommandExecutor operation,
            AgentdProcessIdentity identity,
            RemotePlatform platform,
            Duration timeout,
            RemoteAgentdProcessControl control) throws ProvisioningException, InterruptedException {
        long deadline = saturatingAdd(System.nanoTime(), timeout.toNanos());
        do {
            try {
                publishIdentity(operation, identity, platform);
                return;
            } catch (ProvisioningException failure) {
                if (failure.failure() != ProvisioningFailure.ACTIVATION) {
                    throw failure;
                }
                control.proveUnpublishedIdentity(identity);
                if (System.nanoTime() >= deadline) {
                    throw new ProvisioningException(
                            ProvisioningFailure.UNCERTAIN_IDENTITY,
                            "Exact AgentD identity could not be published within the startup deadline",
                            failure);
                }
                Thread.sleep(25);
            }
        } while (true);
    }

    private static String safeOwnerFileCommand(String path, String success, RemotePlatform platform) {
        String quoted = PosixShell.quote(path);
        String parent = path.substring(0, path.lastIndexOf('/'));
        String stat = switch (platform) {
            case LINUX_X86_64, LINUX_AARCH64 -> "stat -c '%u:%a:%s' -- " + quoted;
            case MACOS_X86_64, MACOS_AARCH64 -> "stat -f '%u:%Lp:%z' -- " + quoted;
        };
        return "uid=$(id -u); " + safeOwnerDirectoryCommand(parent, platform, true)
                + "[ ! -L " + quoted + " ] || exit 79; "
                + "if [ ! -e " + quoted + " ]; then exit 3; fi; [ -f " + quoted + " ] || exit 79; "
                + "metadata=$(" + stat + ") || exit 82; case \"$metadata\" in \"$uid:600:\"*) ;; "
                + "*) exit 79 ;; esac; size=${metadata##*:}; [ \"$size\" -le "
                + AgentdProcessRecord.MAX_BYTES + " ] || exit 79; " + success;
    }

    private static String safeOwnerDirectoryCommand(
            String path, RemotePlatform platform, boolean missingMeansAbsent) {
        String quoted = PosixShell.quote(path);
        String stat = switch (platform) {
            case LINUX_X86_64, LINUX_AARCH64 -> "stat -c '%u:%a' -- " + quoted;
            case MACOS_X86_64, MACOS_AARCH64 -> "stat -f '%u:%Lp' -- " + quoted;
        };
        String missing = missingMeansAbsent ? "exit 3" : "mkdir -m 700 " + quoted + " || exit 73";
        return "[ ! -L " + quoted + " ] || exit 79; if [ ! -e " + quoted + " ]; then " + missing
                + "; fi; [ -d " + quoted + " ] || exit 79; [ \"$(" + stat
                + ")\" = \"$uid:700\" ] || exit 79; ";
    }

    private String identityFile(AgentdLaunchRequest request) {
        return installRoot + "/identities/" + request.generation().value()
                + "-" + request.launchId().value() + ".identity";
    }

    private static String nativeProbe(RemotePlatform platform, long pid) {
        return switch (platform) {
            case LINUX_X86_64, LINUX_AARCH64 -> "pid=" + pid
                    + "; [ -r \"/proc/$pid/stat\" ] || exit 3; line=$(cat \"/proc/$pid/stat\") || exit 82; "
                    + "rest=${line##*) }; set -- $rest; token=${20}; "
                    + "exe=$(readlink \"/proc/$pid/exe\") || exit 82; printf '%s\\n%s\\n' \"$token\" \"$exe\"";
            case MACOS_X86_64, MACOS_AARCH64 -> throw new IllegalStateException(
                    "macOS AgentD replacement must fail before native identity probing");
        };
    }

    private ProvisioningResult install(
            MinaSshOperation operation,
            AgentdLaunchRequest request) throws ProvisioningException {
        ProvisioningResult result = installRelease(operation, request);
        switchCurrent(operation, result.version(), request, result.platform());
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

    void switchCurrent(
            RemoteCommandExecutor operation,
            String version,
            AgentdLaunchRequest request,
            RemotePlatform platform) throws ProvisioningException {
        String next = installRoot + "/current.next-" + request.launchId().value();
        String linkTarget = "releases/" + version;
        String current = installRoot + "/current";
        String atomicMove = switch (platform) {
            case LINUX_X86_64, LINUX_AARCH64 -> "mv -fT ";
            case MACOS_X86_64, MACOS_AARCH64 -> "mv -fh ";
        };
        String switchCommand = "if [ -L " + PosixShell.quote(next) + " ]; then rm -- "
                + PosixShell.quote(next) + " || exit 73; elif [ -e " + PosixShell.quote(next)
                + " ]; then exit 79; fi; ln -s " + PosixShell.quote(linkTarget) + " "
                + PosixShell.quote(next) + " || exit 73; if [ -L " + PosixShell.quote(current)
                + " ] || { [ ! -e " + PosixShell.quote(current) + " ] && [ ! -L "
                + PosixShell.quote(current) + " ]; }; then " + atomicMove + PosixShell.quote(next) + " "
                + PosixShell.quote(current) + " || exit 73; else rm -- " + PosixShell.quote(next)
                + "; exit 79; fi";
        RemoteCommandResult switched = operation.execute(switchCommand, new byte[0]);
        if (switched.exitCode() == 79) {
            throw new ProvisioningException(
                    ProvisioningFailure.UNSAFE_IDENTITY, "Remote runtime switch encountered unsafe state");
        }
        requireZero(switched, ProvisioningFailure.ACTIVATION, "Remote runtime switch failed");
    }

    private static AgentdPartialLaunchException partialIdentityFailure(
            AgentdProcessIdentity identity, ProvisioningException failure) {
        return new AgentdPartialLaunchException(
                failure.failure(), "AgentD reconciliation failed after exact identity proof", identity, failure);
    }

    private void launch(
            RemoteCommandExecutor operation,
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
        String command = "umask 077; uid=$(id -u); "
                + safeOwnerDirectoryCommand(request.stateDirectory(), result.platform(), false)
                + safeOwnerDirectoryCommand(installRoot + "/logs", result.platform(), false)
                + "IFS= read -r permit || exit 64; " + detached
                + " status=$?; unset permit; [ \"$status\" -eq 0 ] || exit \"$status\""
                + "; printf launched";
        RemoteCommandResult launch;
        try {
            launch = operation.execute(command, channelInput);
        } catch (ProvisioningException error) {
            throw new ProvisioningException(
                    ProvisioningFailure.UNCERTAIN_IDENTITY,
                    "Detached AgentD launch result is unknown", error);
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
            RemoteCommandExecutor operation,
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

    private static void requireReplacementPlatform(RemotePlatform platform) throws ProvisioningException {
        if (platform == RemotePlatform.MACOS_X86_64 || platform == RemotePlatform.MACOS_AARCH64) {
            throw new ProvisioningException(
                    ProvisioningFailure.UNCERTAIN_IDENTITY,
                    "AgentD replacement is unsupported on macOS without exact native process identity");
        }
    }

    private static ProvisioningFailure identityReadFailure(RemoteCommandResult result) {
        return result.exitCode() == 79 ? ProvisioningFailure.UNSAFE_IDENTITY
                : ProvisioningFailure.UNCERTAIN_IDENTITY;
    }

    private static long saturatingAdd(long value, long increment) {
        try {
            return Math.addExact(value, increment);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static String stripTrailingSlash(String value) {
        int end = value.length();
        while (end > 1 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }
}
