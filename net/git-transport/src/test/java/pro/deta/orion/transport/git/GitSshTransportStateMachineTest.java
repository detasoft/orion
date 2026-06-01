package pro.deta.orion.transport.git;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.crypto.SshHostKeyService;
import pro.deta.orion.lifecycle.state.StateTransitionFailedException;
import pro.deta.orion.transport.git.ssh.SshCommandFactory;
import pro.deta.orion.util.ConfigurationContext;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.ERR;

class GitSshTransportStateMachineTest {
    @TempDir
    private Path tempDir;

    private GitSshTransportService service;

    @AfterEach
    void stopService() {
        if (service != null) {
            service.onStop();
        }
    }

    @Test
    void bindFailureMovesStateMachineToError() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            service = service(occupied.getLocalPort());
            GitSshTransportStateMachine machine = new GitSshTransportStateMachine(() -> service);

            assertThrows(StateTransitionFailedException.class, machine::start);

            assertEquals(ERR, machine.currentState());
            assertFalse(service.isRunning());
        }
    }

    private GitSshTransportService service(int port) {
        OrionConfiguration configuration = new OrionConfiguration();
        configuration.getBootstrap().setBaseDir(tempDir.toString());
        configuration.getTransport().getSsh().setEnabled(true);
        configuration.getTransport().getSsh().setAddress("127.0.0.1");
        configuration.getTransport().getSsh().setPort(port);
        SshHostKeyService hostKeyService = new SshHostKeyService(new ConfigurationContext(configuration));
        SshCommandFactory commandFactory = new SshCommandFactory(null, null, null, null, null);
        OrionSSHPasswordAuthenticator authenticator = new OrionSSHPasswordAuthenticator(null);
        return new GitSshTransportService(configuration, commandFactory, () -> hostKeyService, authenticator);
    }
}
