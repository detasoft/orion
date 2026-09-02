package pro.deta.orion.transport.git.auth;

import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.signature.Signature;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.server.auth.pubkey.UserAuthPublicKey;
import org.apache.sshd.server.auth.pubkey.UserAuthPublicKeyFactory;
import org.apache.sshd.server.session.ServerSession;

import java.io.IOException;
import java.util.List;

public final class EnrollmentAwarePublicKeyAuthFactory extends UserAuthPublicKeyFactory {
    private final OrionSshAuthenticator authenticator;

    public EnrollmentAwarePublicKeyAuthFactory(OrionSshAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    public UserAuthPublicKey createUserAuth(ServerSession session) throws IOException {
        return new EnrollmentAwareUserAuthPublicKey(getSignatureFactories(), authenticator);
    }

    private static final class EnrollmentAwareUserAuthPublicKey extends UserAuthPublicKey {
        private final OrionSshAuthenticator authenticator;

        private EnrollmentAwareUserAuthPublicKey(
                List<NamedFactory<Signature>> signatureFactories,
                OrionSshAuthenticator authenticator) {
            super(signatureFactories);
            this.authenticator = authenticator;
        }

        @Override
        public Boolean doAuth(Buffer buffer, boolean init) throws Exception {
            try {
                Boolean result = super.doAuth(buffer, init);
                if (Boolean.TRUE.equals(result)) {
                    return authenticator.completePublicKeyAttempt(getServerSession());
                }
                authenticator.abandonPublicKeyAttempt(getServerSession());
                return result;
            } catch (Exception | Error e) {
                authenticator.abandonPublicKeyAttempt(getServerSession());
                throw e;
            }
        }
    }
}
