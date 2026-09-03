package pro.deta.orion.provisioning;

import java.io.IOException;

final class LocalShellExecutor implements RemoteCommandExecutor {
    @Override
    public RemoteCommandResult execute(String command, byte[] input) throws ProvisioningException {
        try {
            Process process = new ProcessBuilder("/bin/sh", "-c", command).start();
            try (var output = process.getOutputStream()) {
                output.write(input);
            }
            byte[] stdout = process.getInputStream().readAllBytes();
            byte[] stderr = process.getErrorStream().readAllBytes();
            int status = process.waitFor();
            return new RemoteCommandResult(status, stdout, stderr, false, false);
        } catch (IOException error) {
            throw new ProvisioningException(
                    ProvisioningFailure.REMOTE_COMMAND, "Local shell command failed", error);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ProvisioningException(
                    ProvisioningFailure.REMOTE_COMMAND, "Local shell command was interrupted", interrupted);
        }
    }
}
