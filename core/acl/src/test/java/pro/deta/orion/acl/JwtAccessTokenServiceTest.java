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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtAccessTokenServiceTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-02T21:00:00Z"), ZoneOffset.UTC);

    @Test
    void issuesPurposeBoundAccessTokenWithCompleteTimeAndIdentityClaims() throws Exception {
        JwtAccessTokenService service = new JwtAccessTokenService(
                TestIdentity.single("server-signing-v1"), CLOCK);

        JwtAccessTokenService.IssuedToken token = service.issue("alice", 600);

        String payload = payload(token.value());
        assertThat(payload)
                .contains("\"iss\":\"orion\"")
                .contains("\"aud\":\"orion\"")
                .contains("\"sub\":\"alice\"")
                .contains("\"purpose\":\"orion-access\"")
                .contains("\"iat\":1788382800")
                .contains("\"nbf\":1788382800")
                .contains("\"exp\":1788383400");
        UUID.fromString(stringClaim(payload, "jti"));
    }

    @Test
    void rejectsTokenWithoutAudience() throws Exception {
        TestIdentity identity = TestIdentity.single("server-signing-v1");
        JwtAccessTokenService service = new JwtAccessTokenService(identity, CLOCK);
        String token = signedToken(
                identity,
                "{\"iss\":\"orion\",\"sub\":\"alice\",\"purpose\":\"orion-access\","
                        + "\"jti\":\"1fc784e3-3238-4279-8627-d8a2e64bc17f\","
                        + "\"iat\":1788382800,\"nbf\":1788382800,\"exp\":1788383400}");

        assertThat(service.verify(token))
                .isEqualTo(JwtAccessTokenService.VerificationResult.failure(
                        "JWT audience is invalid"));
    }

    @Test
    void rejectsExpirationBeyondShortLivedTokenLimit() throws Exception {
        JwtAccessTokenService service = new JwtAccessTokenService(
                TestIdentity.single("server-signing-v1"), CLOCK);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.issue("alice", 3_601))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Token expiration exceeds 3600 seconds");
    }

    @Test
    void rejectsTokenWithoutAccessPurpose() throws Exception {
        TestIdentity identity = TestIdentity.single("server-signing-v1");
        JwtAccessTokenService service = new JwtAccessTokenService(identity, CLOCK);
        String token = signedToken(identity, validPayload().replace(
                ",\"purpose\":\"orion-access\"", ""));

        assertThat(service.verify(token))
                .isEqualTo(JwtAccessTokenService.VerificationResult.failure(
                        "JWT purpose is invalid"));
    }

    @Test
    void rejectsTokenWithoutValidTokenId() throws Exception {
        TestIdentity identity = TestIdentity.single("server-signing-v1");
        JwtAccessTokenService service = new JwtAccessTokenService(identity, CLOCK);
        String token = signedToken(identity, validPayload().replace(
                "1fc784e3-3238-4279-8627-d8a2e64bc17f", "not-a-uuid"));

        assertThat(service.verify(token))
                .isEqualTo(JwtAccessTokenService.VerificationResult.failure(
                        "JWT token id is invalid"));
    }

    @Test
    void rejectsTokenWithoutIssuedAtOrNotBefore() throws Exception {
        TestIdentity identity = TestIdentity.single("server-signing-v1");
        JwtAccessTokenService service = new JwtAccessTokenService(identity, CLOCK);
        String withoutIssuedAt = signedToken(identity, validPayload().replace(
                ",\"iat\":1788382800", ""));
        String withoutNotBefore = signedToken(identity, validPayload().replace(
                ",\"nbf\":1788382800", ""));

        assertThat(service.verify(withoutIssuedAt))
                .isEqualTo(JwtAccessTokenService.VerificationResult.failure(
                        "JWT issued-at is required"));
        assertThat(service.verify(withoutNotBefore))
                .isEqualTo(JwtAccessTokenService.VerificationResult.failure(
                        "JWT not-before is required"));
    }

    @Test
    void rejectsSignedTokenWhoseLifetimeExceedsLimit() throws Exception {
        TestIdentity identity = TestIdentity.single("server-signing-v1");
        JwtAccessTokenService service = new JwtAccessTokenService(identity, CLOCK);
        String token = signedToken(identity, validPayload().replace(
                "\"exp\":1788383400", "\"exp\":1788386401"));

        assertThat(service.verify(token))
                .isEqualTo(JwtAccessTokenService.VerificationResult.failure(
                        "JWT lifetime exceeds 3600 seconds"));
    }

    @Test
    void rejectsTokenThatExpiresBeforeItBecomesActive() throws Exception {
        TestIdentity identity = TestIdentity.single("server-signing-v1");
        JwtAccessTokenService service = new JwtAccessTokenService(identity, CLOCK);
        String token = signedToken(identity, validPayload()
                .replace("\"nbf\":1788382800", "\"nbf\":1788383500"));

        assertThat(service.verify(token))
                .isEqualTo(JwtAccessTokenService.VerificationResult.failure(
                        "JWT time range is invalid"));
    }

    @Test
    void returnsTokenIdInVerifiedIdentity() throws Exception {
        JwtAccessTokenService service = new JwtAccessTokenService(
                TestIdentity.single("server-signing-v1"), CLOCK);
        JwtAccessTokenService.IssuedToken token = service.issue("alice", 600);

        JwtAccessTokenService.VerificationResult result = service.verify(token.value());

        assertThat(result).isInstanceOf(JwtAccessTokenService.VerificationResult.Success.class);
        JwtAccessTokenService.VerificationResult.Success success =
                (JwtAccessTokenService.VerificationResult.Success) result;
        assertThat(success.tokenId())
                .isEqualTo(stringClaim(payload(token.value()), "jti"));
    }

    @Test
    void usesActiveMaterialAliasAsKid() throws Exception {
        TestIdentity identity = TestIdentity.single("server-signing-v2");
        JwtAccessTokenService service = new JwtAccessTokenService(identity, CLOCK);

        JwtAccessTokenService.IssuedToken token = service.issue("alice", 600);

        assertThat(header(token.value())).contains("\"kid\":\"server-signing-v2\"");
        assertVerified(service, token.value(), "alice", null);
    }

    @Test
    void escapesConfiguredAliasInKid() throws Exception {
        String alias = "server-\"signing\\v2";
        JwtAccessTokenService service = new JwtAccessTokenService(TestIdentity.single(alias), CLOCK);

        JwtAccessTokenService.IssuedToken token = service.issue("alice", 600);

        assertThat(header(token.value())).contains("\"kid\":\"server-\\\"signing\\\\v2\"");
        assertVerified(service, token.value(), "alice", null);
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

        assertVerified(rotatedService, oldToken, "alice", null);
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

    @Test
    void roundTripsOptionalAuthenticationGeneration() throws Exception {
        JwtAccessTokenService service = new JwtAccessTokenService(
                TestIdentity.single("server-signing-v1"), CLOCK);

        JwtAccessTokenService.IssuedToken token = service.issue("root", 600, "generation-1");

        assertThat(payload(token.value())).contains("\"orion_auth_generation\":\"generation-1\"");
        assertVerified(service, token.value(), "root", "generation-1");
    }

    @Test
    void claimFreeTokensRemainCompatible() throws Exception {
        JwtAccessTokenService service = new JwtAccessTokenService(
                TestIdentity.single("server-signing-v1"), CLOCK);

        JwtAccessTokenService.IssuedToken token = service.issue("alice", 600);

        assertThat(payload(token.value())).doesNotContain("orion_auth_generation");
        assertVerified(service, token.value(), "alice", null);
    }

    @Test
    void rejectsMalformedAuthenticationGenerationClaim() throws Exception {
        TestIdentity identity = TestIdentity.single("server-signing-v1");
        JwtAccessTokenService service = new JwtAccessTokenService(identity, CLOCK);
        String malformedToken = signedToken(
                identity,
                validPayload().replace(
                        "\"sub\":\"alice\"",
                        "\"sub\":\"root\"").replace(
                        "}",
                        ",\"orion_auth_generation\":42}"));

        assertThat(service.verify(malformedToken))
                .isEqualTo(JwtAccessTokenService.VerificationResult.failure(
                        "JWT authentication generation is invalid"));
    }

    private static String header(String token) {
        String encoded = token.substring(0, token.indexOf('.'));
        return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    private static String payload(String token) {
        String[] parts = token.split("\\.");
        return new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
    }

    private static String stringClaim(String json, String claim) {
        String prefix = "\"" + claim + "\":\"";
        int start = json.indexOf(prefix);
        assertThat(start).isGreaterThanOrEqualTo(0);
        int valueStart = start + prefix.length();
        int end = json.indexOf('"', valueStart);
        assertThat(end).isGreaterThan(valueStart);
        return json.substring(valueStart, end);
    }

    private static void assertVerified(
            JwtAccessTokenService service,
            String token,
            String subject,
            String authenticationGeneration) {
        assertThat(service.verify(token))
                .isEqualTo(JwtAccessTokenService.VerificationResult.success(
                        subject,
                        authenticationGeneration,
                        stringClaim(payload(token), "jti")));
    }

    private static String signedToken(TestIdentity identity, String payload) throws GeneralSecurityException {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\""
                + identity.activeKeyId()
                + "\"}";
        String signingInput = encoder.encodeToString(header.getBytes(StandardCharsets.UTF_8))
                + "."
                + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + encoder.encodeToString(
                identity.sign(signingInput.getBytes(StandardCharsets.US_ASCII)));
    }

    private static String validPayload() {
        return "{\"iss\":\"orion\",\"aud\":\"orion\",\"sub\":\"alice\","
                + "\"purpose\":\"orion-access\","
                + "\"jti\":\"1fc784e3-3238-4279-8627-d8a2e64bc17f\","
                + "\"iat\":1788382800,\"nbf\":1788382800,\"exp\":1788383400}";
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
