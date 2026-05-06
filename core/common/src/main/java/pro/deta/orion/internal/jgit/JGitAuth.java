package pro.deta.orion.internal.jgit;

import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import pro.deta.orion.internal.auth.Auth;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.List;
import java.util.Optional;

public final class JGitAuth {
    private JGitAuth() {
    }

    public static void injectIntoGitCommand(
            Auth auth,
            TransportCommand<?, ?> gitCommand,
            OrionClientSshdSessionFactoryProvider orionClientSshdSessionFactoryProvider) {
        switch (auth) {
            case Auth.HttpAuth httpAuth -> injectUsernameAndPassword(gitCommand, httpAuth.username(), httpAuth.password());
            case Auth.LocalSshAuthKeyPair localSshAuthKeyPair ->
                    injectAuthKeyPair(gitCommand, localSshAuthKeyPair.keyPair(), localSshAuthKeyPair.publicKeys(), orionClientSshdSessionFactoryProvider);
            case Auth.LocalNoneAuth noneAuth -> {
            }
            case Auth.SshAuthKey sshAuthKey -> injectAuthKeyPair(gitCommand, sshAuthKey.keyPair(), List.of(), orionClientSshdSessionFactoryProvider);
            case Auth.SshAuthKeyPair sshAuthKeyPair -> injectAuthKeyPair(gitCommand, sshAuthKeyPair.keyPair(), List.of(), orionClientSshdSessionFactoryProvider);
        }
    }

    private static void injectUsernameAndPassword(TransportCommand<?, ?> command, String username, String password) {
        command.setCredentialsProvider(new UsernamePasswordCredentialsProvider(username, password));
    }

    private static void injectAuthKeyPair(
            TransportCommand<?, ?> command,
            Optional<KeyPair> keyPair,
            List<PublicKey> publicKeys,
            OrionClientSshdSessionFactoryProvider orionClientSshdSessionFactoryProvider) {
        if (keyPair.isPresent()) {
            command.setTransportConfigCallback(transport -> {
                SshTransport sshTransport = (SshTransport) transport;
                sshTransport.setSshSessionFactory(orionClientSshdSessionFactoryProvider.create(keyPair.get(), publicKeys));
            });
        }
    }
}
