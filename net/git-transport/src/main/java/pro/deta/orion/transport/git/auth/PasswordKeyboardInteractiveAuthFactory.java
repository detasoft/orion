package pro.deta.orion.transport.git.auth;

import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.SshException;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.server.auth.AbstractUserAuth;
import org.apache.sshd.server.auth.AbstractUserAuthFactory;
import org.apache.sshd.server.auth.UserAuth;
import org.apache.sshd.server.auth.keyboard.InteractiveChallenge;
import org.apache.sshd.server.session.ServerSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PasswordKeyboardInteractiveAuthFactory extends AbstractUserAuthFactory {
    private final OrionSshAuthenticator authenticator;

    public PasswordKeyboardInteractiveAuthFactory(OrionSshAuthenticator authenticator) {
        super("keyboard-interactive");
        this.authenticator = authenticator;
    }

    @Override
    public UserAuth createUserAuth(ServerSession session) {
        return new PasswordKeyboardInteractiveAuth(authenticator);
    }

    private static final class PasswordKeyboardInteractiveAuth extends AbstractUserAuth {
        private final OrionSshAuthenticator authenticator;
        private Phase phase;
        private OrionSshAuthenticator.PasswordAuthentication passwordAuthentication;

        private PasswordKeyboardInteractiveAuth(OrionSshAuthenticator authenticator) {
            super("keyboard-interactive");
            this.authenticator = authenticator;
        }

        @Override
        protected Boolean doAuth(Buffer buffer, boolean init) throws Exception {
            if (init) {
                buffer.getString();
                buffer.getString();
                if (!authenticator.allowsKeyboardInteractive(getServerSession(), getUsername())) {
                    return false;
                }
                phase = Phase.PASSWORD;
                sendChallenge(authenticator.passwordChallenge());
                return null;
            }

            List<String> responses = readResponses(buffer);
            try {
                if (phase == null) {
                    return false;
                }
                return switch (phase) {
                    case PASSWORD -> authenticatePassword(responses);
                    case KEY_SELECTION -> authenticateSelection(responses);
                };
            } finally {
                responses.clear();
            }
        }

        private Boolean authenticatePassword(List<String> responses) throws Exception {
            if (responses.size() != 1) {
                return authenticator.failKeyboardInteractive(getServerSession());
            }
            passwordAuthentication = authenticator.authenticatePassword(
                    getServerSession(),
                    getUsername(),
                    responses.getFirst());
            if (passwordAuthentication == null) {
                return authenticator.failKeyboardInteractive(getServerSession());
            }
            if (passwordAuthentication.candidates().isEmpty()
                    && passwordAuthentication.rootRecoveryGeneration() == null) {
                return authenticator.completePasswordAuthentication(
                        getServerSession(),
                        getUsername(),
                        passwordAuthentication,
                        null);
            }
            phase = Phase.KEY_SELECTION;
            sendChallenge(authenticator.selectionChallenge(passwordAuthentication.candidates()));
            return null;
        }

        private Boolean authenticateSelection(List<String> responses) {
            if (responses.size() != 1) {
                return authenticator.failKeyboardInteractive(getServerSession());
            }
            try {
                boolean authenticated = authenticator.completePasswordAuthentication(
                        getServerSession(),
                        getUsername(),
                        passwordAuthentication,
                        responses.getFirst());
                if (authenticated) {
                    return true;
                }
                return authenticator.failKeyboardInteractive(getServerSession());
            } finally {
                passwordAuthentication = null;
            }
        }

        @Override
        public void destroy() {
            passwordAuthentication = null;
            super.destroy();
        }

        private void sendChallenge(InteractiveChallenge challenge) throws Exception {
            Buffer packet = getServerSession().createBuffer(SshConstants.SSH_MSG_USERAUTH_INFO_REQUEST);
            challenge.append(packet);
            getServerSession().writePacket(packet);
        }

        private List<String> readResponses(Buffer buffer) throws Exception {
            int command = buffer.getUByte();
            if (command != SshConstants.SSH_MSG_USERAUTH_INFO_RESPONSE) {
                throw new SshException("Received unexpected message: "
                        + SshConstants.getCommandMessageName(command));
            }
            int count = buffer.getInt();
            if (count < 0 || count > SshConstants.SSH_REQUIRED_PAYLOAD_PACKET_LENGTH_SUPPORT) {
                throw new IndexOutOfBoundsException("Illogical response count: " + count);
            }
            if (count == 0) {
                return new ArrayList<>(Collections.emptyList());
            }
            List<String> responses = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                responses.add(buffer.getString());
            }
            return responses;
        }

        private enum Phase {
            PASSWORD,
            KEY_SELECTION
        }
    }
}
