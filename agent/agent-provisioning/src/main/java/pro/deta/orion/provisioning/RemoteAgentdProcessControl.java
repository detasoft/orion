package pro.deta.orion.provisioning;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.function.LongSupplier;

public final class RemoteAgentdProcessControl {
    public enum ProcessState { MATCHING, GONE }

    private final RemoteCommandExecutor executor;
    private final RemotePlatform platform;
    private final String stateDirectory;
    private final String installRoot;
    private final LongSupplier nanoTime;
    private final AgentdSleeper sleeper;

    public RemoteAgentdProcessControl(
            RemoteCommandExecutor executor,
            RemotePlatform platform,
            String stateDirectory,
            String installRoot,
            LongSupplier nanoTime,
            AgentdSleeper sleeper) {
        if (executor == null || platform == null || nanoTime == null || sleeper == null) {
            throw new IllegalArgumentException("Remote AgentD process control arguments must not be null");
        }
        this.executor = executor;
        this.platform = platform;
        this.stateDirectory = requireAbsolute(stateDirectory);
        this.installRoot = requireAbsolute(installRoot);
        this.nanoTime = nanoTime;
        this.sleeper = sleeper;
    }

    public ProcessState inspect(AgentdProcessIdentity expected) throws ProvisioningException {
        requireSupported(expected, "inspection");
        RemoteCommandResult result = executor.execute(verificationCommand(expected, null), new byte[0]);
        if (result.exitCode() == 3) {
            return ProcessState.GONE;
        }
        requireSuccess(result, "inspection", expected);
        try {
            AgentdProcessIdentity actual = AgentdProcessRecord.parse(result.stdoutText());
            if (!expected.equals(actual)) {
                throw failure(ProvisioningFailure.UNCERTAIN_IDENTITY, "AgentD identity inspection disagreed");
            }
            return ProcessState.MATCHING;
        } catch (IllegalArgumentException malformed) {
            throw new ProvisioningException(
                    ProvisioningFailure.MALFORMED_IDENTITY, "AgentD identity response is malformed", malformed);
        }
    }

    void proveUnpublishedIdentity(AgentdProcessIdentity expected) throws ProvisioningException {
        requireSupported(expected, "unpublished lock inspection");
        RemoteCommandResult result = executor.execute(unpublishedVerificationCommand(expected), new byte[0]);
        requireSuccess(result, "unpublished lock inspection", expected);
    }

    public void terminate(AgentdProcessIdentity expected, AgentdRecoveryOptions options)
            throws ProvisioningException, InterruptedException {
        requireSupported(expected, "termination");
        RemoteCommandResult term = executor.execute(verificationCommand(expected, "TERM"), new byte[0]);
        if (isGone(term)) {
            return;
        }
        requireSuccess(term, "TERM", expected);
        if (awaitExit(expected, options.terminationGrace())) {
            return;
        }
        RemoteCommandResult kill = executor.execute(verificationCommand(expected, "KILL"), new byte[0]);
        if (isGone(kill)) {
            return;
        }
        requireSuccess(kill, "KILL", expected);
        if (!awaitExit(expected, options.killConfirmationTimeout())) {
            throw failure(ProvisioningFailure.TERMINATION_TIMEOUT,
                    "AgentD PID " + expected.pid() + " did not terminate within configured bounds");
        }
    }

    private boolean awaitExit(AgentdProcessIdentity expected, Duration timeout)
            throws ProvisioningException, InterruptedException {
        long deadline = saturatingAdd(nanoTime.getAsLong(), timeout.toNanos());
        do {
            RemoteCommandResult probe = executor.execute(liveProbeCommand(expected), new byte[0]);
            if (isGone(probe)) {
                return true;
            }
            requireSuccess(probe, "termination confirmation", expected);
            if (nanoTime.getAsLong() >= deadline) {
                return false;
            }
            sleeper.sleep(Duration.ofMillis(25));
        } while (true);
    }

    private String verificationCommand(AgentdProcessIdentity expected, String signal) {
        StringBuilder command = new StringBuilder(metadataVerificationPrefix(expected))
                .append(platformProbe(expected))
                .append(lockOwnershipProof(expected));
        if (signal == null) {
            command.append("cat -- ").append(PosixShell.quote(identityFile(expected)));
        } else {
            command.append("kill_error=$(kill -").append(signal)
                    .append(" \"$pid\" 2>&1); signal_status=$?; ")
                    .append("if [ \"$signal_status\" -ne 0 ]; then ")
                    .append(platformProbe(expected))
                    .append("printf '%s' \"$kill_error\" >&2; exit 81; fi; printf signalled");
        }
        return command.toString();
    }

    String metadataVerificationCommand(AgentdProcessIdentity expected) {
        return metadataVerificationPrefix(expected) + "printf verified";
    }

    private String unpublishedVerificationCommand(AgentdProcessIdentity expected) {
        String lock = stateDirectory + "/agentd.lock";
        String expectedLock = expectedLock(expected);
        return new StringBuilder("set -f; uid=$(id -u); ")
                .append(safetyCheck(stateDirectory, true))
                .append(safetyCheck(lock, false))
                .append("lock=$(base64 < ").append(PosixShell.quote(lock))
                .append(" | tr -d '\\n') || exit 82; ")
                .append("[ \"$lock\" = ").append(PosixShell.quote(encode(expectedLock))).append(" ] || exit 80; ")
                .append("pid=").append(expected.pid()).append("; ")
                .append(lockOwnershipProof(expected))
                .append(platformProbe(expected))
                .append("printf verified")
                .toString();
    }

    private String metadataVerificationPrefix(AgentdProcessIdentity expected) {
        String record = identityFile(expected);
        String lock = stateDirectory + "/agentd.lock";
        String expectedRecord = AgentdProcessRecord.serialize(expected);
        String expectedLock = expectedLock(expected);
        String encodedRecord = encode(expectedRecord);
        String encodedLock = encode(expectedLock);
        return new StringBuilder("set -f; uid=$(id -u); ")
                .append(safetyCheck(stateDirectory, true))
                .append(safetyCheck(stateDirectory + "/agentd.lock", false))
                .append(safetyCheck(installRoot + "/identities", true))
                .append(safetyCheck(record, false))
                .append("record=$(base64 < ").append(PosixShell.quote(record))
                .append(" | tr -d '\\n') || exit 82; ")
                .append("lock=$(base64 < ").append(PosixShell.quote(lock))
                .append(" | tr -d '\\n') || exit 82; ")
                .append("[ \"$record\" = ").append(PosixShell.quote(encodedRecord)).append(" ] || exit 80; ")
                .append("[ \"$lock\" = ").append(PosixShell.quote(encodedLock)).append(" ] || exit 80; ")
                .toString();
    }

    private static String expectedLock(AgentdProcessIdentity expected) {
        return "version=2\npid=" + expected.pid()
                + "\nstartEpochMillis=" + expected.startEpochMillis()
                + "\nlaunchId=" + expected.launchId().value()
                + "\ngeneration=" + expected.generation().value()
                + "\nexecutable=" + expected.executable() + "\n";
    }

    private String safetyCheck(String path, boolean directory) {
        String quoted = PosixShell.quote(path);
        String kind = directory ? "d" : "f";
        String mode = directory ? "700" : "600";
        String stat = switch (platform) {
            case LINUX_X86_64, LINUX_AARCH64 ->
                    "stat -c '%u:%a" + (directory ? "" : ":%s") + "' -- " + quoted;
            case MACOS_X86_64, MACOS_AARCH64 ->
                    "stat -f '%u:%Lp" + (directory ? "" : ":%z") + "' -- " + quoted;
        };
        String expected = directory ? "$uid:" + mode : "$uid:" + mode + ":";
        String sizeCheck = directory ? "" : "metadata=$(" + stat + ") || exit 82; "
                + "case \"$metadata\" in \"" + expected + "\"*) ;; *) exit 79 ;; esac; "
                + "size=${metadata##*:}; [ \"$size\" -le " + AgentdProcessRecord.MAX_BYTES + " ] || exit 79; ";
        String modeCheck = directory
                ? "[ \"$(" + stat + ")\" = \"$uid:" + mode + "\" ] || exit 79; " : sizeCheck;
        return "[ ! -L " + quoted + " ] || exit 79; [ -" + kind + " " + quoted + " ] || exit 79; "
                + modeCheck;
    }

    private String platformProbe(AgentdProcessIdentity expected) {
        String common = "pid=" + expected.pid() + "; ";
        return switch (platform) {
            case LINUX_X86_64, LINUX_AARCH64 -> common
                    + "[ -r \"/proc/$pid/stat\" ] || { [ -e \"/proc/$pid\" ] && exit 82 || exit 3; }; "
                    + "line=$(cat \"/proc/$pid/stat\") || exit 82; rest=${line##*) }; "
                    + "set -- $rest; token=${20}; "
                    + "exe=$(readlink \"/proc/$pid/exe\") || exit 82; "
                    + "[ \"$token\" = " + PosixShell.quote(expected.nativeStartToken()) + " ] || exit 3; "
                    + "[ \"$exe\" = " + PosixShell.quote(expected.executable()) + " ] || exit 80; ";
            case MACOS_X86_64, MACOS_AARCH64 -> throw new IllegalStateException(
                    "macOS process identity must be rejected before command rendering");
        };
    }

    private String lockOwnershipProof(AgentdProcessIdentity expected) {
        if (platform == RemotePlatform.MACOS_X86_64 || platform == RemotePlatform.MACOS_AARCH64) {
            throw new IllegalStateException("macOS process lock proof is unsupported");
        }
        String lock = PosixShell.quote(stateDirectory + "/agentd.lock");
        return "held=0; for fd in \"/proc/$pid/fd/\"*; do "
                + "if [ \"$fd\" -ef " + lock + " ]; then fd_number=${fd##*/}; "
                + "awk -v owner=\"$pid\" '$1 == \"lock:\" && $3 == \"POSIX\" "
                + "&& $5 == \"WRITE\" && $6 == owner { found=1 } END { exit !found }' "
                + "\"/proc/$pid/fdinfo/$fd_number\" && { held=1; break; }; fi; done; "
                + "[ \"$held\" = 1 ] || exit 80; ";
    }

    private String liveProbeCommand(AgentdProcessIdentity expected) {
        return platformProbe(expected) + "printf alive";
    }

    private String identityFile(AgentdProcessIdentity identity) {
        return installRoot + "/identities/" + identity.generation().value()
                + "-" + identity.launchId().value() + ".identity";
    }

    private static void requireSuccess(
            RemoteCommandResult result,
            String phase,
            AgentdProcessIdentity expected) throws ProvisioningException {
        if (result.exitCode() == 0) {
            return;
        }
        ProvisioningFailure failure = switch (result.exitCode()) {
            case 3 -> ProvisioningFailure.UNCERTAIN_IDENTITY;
            case 79 -> ProvisioningFailure.UNSAFE_IDENTITY;
            case 80 -> ProvisioningFailure.UNCERTAIN_IDENTITY;
            case 81 -> ProvisioningFailure.SIGNAL_PRIVILEGE;
            case 82 -> ProvisioningFailure.UNCERTAIN_IDENTITY;
            default -> ProvisioningFailure.MALFORMED_IDENTITY;
        };
        throw failure(failure, "AgentD " + phase + " failed for PID " + expected.pid()
                + " (lock " + expected.launchId().value() + "): " + bounded(result.stderrText()));
    }

    private static boolean isGone(RemoteCommandResult result) {
        return result.exitCode() == 3;
    }

    private static ProvisioningException failure(ProvisioningFailure failure, String message) {
        return new ProvisioningException(failure, message);
    }

    private static String bounded(String value) {
        return value.length() <= 512 ? value : value.substring(0, 512);
    }

    private static String requireAbsolute(String path) {
        if (path == null || !path.startsWith("/") || path.indexOf('\0') >= 0
                || path.indexOf('\r') >= 0 || path.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Remote AgentD path must be absolute");
        }
        return path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
    }

    private void requireSupported(AgentdProcessIdentity expected, String phase) throws ProvisioningException {
        if (platform == RemotePlatform.MACOS_X86_64 || platform == RemotePlatform.MACOS_AARCH64) {
            throw failure(
                    ProvisioningFailure.UNCERTAIN_IDENTITY,
                    "AgentD " + phase + " is unsupported on macOS because exact native process identity "
                            + "cannot be proven safely for PID " + expected.pid());
        }
    }

    private static long saturatingAdd(long value, long addition) {
        long result = value + addition;
        return result < value ? Long.MAX_VALUE : result;
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
