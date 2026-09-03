package pro.deta.orion.provisioning;

import java.io.IOException;
import java.io.InputStream;

@FunctionalInterface
public interface RemoteCommandExecutor {
    RemoteCommandResult execute(String command, byte[] input) throws ProvisioningException;

    default RemoteCommandResult execute(String command, InputStream input) throws ProvisioningException {
        try {
            return execute(command, input.readAllBytes());
        } catch (IOException error) {
            throw new ProvisioningException(
                    ProvisioningFailure.TRANSFER, "Remote command input could not be read", error);
        }
    }
}
