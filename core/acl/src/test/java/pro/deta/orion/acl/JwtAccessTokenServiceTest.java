package pro.deta.orion.acl;

import org.junit.jupiter.api.Test;
import pro.deta.orion.keymaterial.ServerIdentityCapability;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JwtAccessTokenServiceTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-02T21:00:00Z"), ZoneOffset.UTC);

    @Test
    void usesActiveMaterialAliasAsKid() throws Exception {
        TestIdentity identity = TestIdentity.single("server-signing-v2");
        JwtAccessTokenService service = new JwtAccessTokenService(identity, CLOCK);

        JwtAccessTokenService.IssuedToken token = service.issue("alice", 600);

        assertThat(header(token.value())).contains("\"kid\":\"server-signing-v2\"");
        assertThat(service.verify(token.value()))
                .isEqualTo(JwtAccessTokenService.VerificationResult.success("alice"));
    }

    @Test
    void escapesConfiguredAliasInKid() throws Exception {
        String alias = "server-\"signing\\v2";
        JwtAccessTokenService service = new JwtAccessTokenService(TestIdentity.single(alias), CLOCK);

        JwtAccessTokenService.IssuedToken token = service.issue("alice", 600);

        assertThat(header(token.value())).contains("\"kid\":\"server-\\\"signing\\\\v2\"");
        assertThat(service.verify(token.value()))
                .isEqualTo(JwtAccessTokenService.VerificationResult.success("alice"));
    }

    @Test
    void rotatedIdentityVerifiesRetainedAliasAndIssuesWithNewAlias() throws Exception {
        KeyPair oldKey = rsaKeyPair();
        KeyPair newKey = rsaKeyPair();
        JwtAccessTokenService oldService = new JwtAccessTokenService(
                new TestIdentity("server-signing-v1", Map.of("server-signing-v1", oldKey)),
                CLOCK);
        String oldToken = oldService.issue("alice", 600).value();
        TestIdentity rotated = new TestIdentity(
                "server-signing-v2",
                orderedKeys("server-signing-v2", newKey, "server-signing-v1", oldKey));
        JwtAccessTokenService rotatedService = new JwtAccessTokenService(rotated, CLOCK);

        String newToken = rotatedService.issue("alice", 600).value();

        assertThat(rotatedService.verify(oldToken))
                .isEqualTo(JwtAccessTokenService.VerificationResult.success("alice"));
        assertThat(header(newToken)).contains("\"kid\":\"server-signing-v2\"");
    }

    @Test
    void rejectsTokenWhoseExactKidIsNotConfigured() throws Exception {
        TestIdentity unknown = TestIdentity.single("unknown-signing");
        String token = new JwtAccessTokenService(unknown, CLOCK).issue("alice", 600).value();
        JwtAccessTokenService configured = new JwtAccessTokenService(
                TestIdentity.single("server-signing-v1"), CLOCK);

        assertThat(configured.verify(token))
                .isEqualTo(JwtAccessTokenService.VerificationResult.failure(
                        "JWT signing key is unknown"));
    }

    private static String header(String token) {
        String encoded = token.substring(0, token.indexOf('.'));
        return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    private static Map<String, KeyPair> orderedKeys(
            String firstAlias,
            KeyPair first,
            String secondAlias,
            KeyPair second) {
        Map<String, KeyPair> keys = new LinkedHashMap<>();
        keys.put(firstAlias, first);
        keys.put(secondAlias, second);
        return keys;
    }

    private static KeyPair rsaKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private record TestIdentity(
            String activeKeyId,
            Map<String, KeyPair> keys) implements ServerIdentityCapability {
        private TestIdentity {
            keys = Map.copyOf(keys);
        }

        private static TestIdentity single(String alias) throws GeneralSecurityException {
            return new TestIdentity(alias, Map.of(alias, rsaKeyPair()));
        }

        @Override
        public byte[] sign(byte[] payload) throws GeneralSecurityException {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(keys.get(activeKeyId).getPrivate());
            signer.update(payload);
            return signer.sign();
        }

        @Override
        public boolean hasVerificationKey(String keyId) {
            return keyId != null && keys.containsKey(keyId);
        }

        @Override
        public boolean verify(
                String keyId,
                byte[] payload,
                byte[] signature) throws GeneralSecurityException {
            if (!keys.containsKey(keyId)) {
                return false;
            }
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(keys.get(keyId).getPublic());
            verifier.update(payload);
            return verifier.verify(signature);
        }

        @Override
        public List<PublicKey> publicKeys() {
            return keys.values().stream().map(KeyPair::getPublic).toList();
        }

        @Override
        public List<PublicKey> retainedPublicKeys() {
            return List.of();
        }
    }
}
