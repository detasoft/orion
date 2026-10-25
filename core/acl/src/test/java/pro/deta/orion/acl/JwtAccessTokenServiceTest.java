package pro.deta.orion.acl;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import pro.deta.orion.keymaterial.OrionKeyMaterial;
import pro.deta.orion.keymaterial.SigningMaterialSet;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
import pro.deta.orion.keymaterial.KeyMaterialPurpose;
import pro.deta.orion.keymaterial.KeyMaterialAlgorithm;
import pro.deta.orion.keymaterial.KeyMaterialOptions;
import pro.deta.orion.keymaterial.InMemoryKeyMaterialContentStore;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtAccessTokenServiceTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-02T21:00:00Z"), ZoneOffset.UTC);

    private final List<OrionKeyMaterial> materials = new ArrayList<>();

    @AfterEach
    void closeMaterials() {
        for (OrionKeyMaterial material : materials) material.close();
    }

    @Test
    void preservesOrganizationScopeSeparatelyFromTheLocalUserId() throws Exception {
        JwtAccessTokenService service = service(single("key"), CLOCK);
        String token = service.issue("root", 600, "oidc-binding", "acme").value();
        assertThat(service.verify(token)).isInstanceOfSatisfying(
                JwtAccessTokenService.VerificationResult.Success.class, result -> {
                    assertThat(result.subject()).isEqualTo("root");
                    assertThat(result.organization()).isEqualTo("acme");
                    assertThat(result.authenticationGeneration()).isEqualTo("oidc-binding");
                });
        assertThat(service.verify(service.issue("root", 600, null).value())).isInstanceOfSatisfying(
                JwtAccessTokenService.VerificationResult.Success.class,
                result -> assertThat(result.organization()).isNull());
    }

    @Test
    void issuesPurposeBoundAccessTokenWithCompleteTimeAndIdentityClaims() throws Exception {
        JwtAccessTokenService service = service(
                single("server-signing-v1"), CLOCK);

        JwtAccessTokenService.IssuedToken token = service.issue("alice", 600, null);

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
    void acceptsTokenUntilItsExactExpirationBoundary() throws Exception {
        TestIdentity identity = single("server-signing-v1");
        JwtAccessTokenService issuer = service(identity, CLOCK);
        JwtAccessTokenService.IssuedToken token = issuer.issue("alice", 600, null);

        JwtAccessTokenService beforeExpiration = service(
                identity,
                Clock.fixed(Instant.ofEpochSecond(token.expiresAtEpochSecond() - 1), ZoneOffset.UTC));
        JwtAccessTokenService atExpiration = service(
                identity,
                Clock.fixed(Instant.ofEpochSecond(token.expiresAtEpochSecond()), ZoneOffset.UTC));

        assertVerified(beforeExpiration, token.value(), "alice", null);
        assertThat(atExpiration.verify(token.value()))
                .isEqualTo(JwtAccessTokenService.VerificationResult.failure("JWT is expired"));
    }

    @Test
    void issuedTokenDoesNotExposeTokenContentsInDiagnostics() {
        JwtAccessTokenService.IssuedToken token = new JwtAccessTokenService.IssuedToken(
                "issued-token-secret", 1_100);

        assertThat(token.toString()).doesNotContain("issued-token-secret");
    }

    @Test
    void rejectsTokenWithoutAudience() throws Exception {
        TestIdentity identity = single("server-signing-v1");
        JwtAccessTokenService service = service(identity, CLOCK);
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
        JwtAccessTokenService service = service(
                single("server-signing-v1"), CLOCK);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.issue("alice", 3_601, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Token expiration exceeds 3600 seconds");
    }

    @Test
    void issuesAndVerifiesTokenAtMaximumLifetime() throws Exception {
        JwtAccessTokenService service = service(
                single("server-signing-v1"), CLOCK);
        JwtAccessTokenService.IssuedToken token = service.issue("alice", 3_600, null);
        assertThat(token.expiresAtEpochSecond()).isEqualTo(CLOCK.instant().getEpochSecond() + 3_600);
        assertThat(service.verify(token.value())).isInstanceOf(JwtAccessTokenService.VerificationResult.Success.class);
    }

    @Test
    void rejectsTokenWithoutAccessPurpose() throws Exception {
        TestIdentity identity = single("server-signing-v1");
        JwtAccessTokenService service = service(identity, CLOCK);
        String token = signedToken(identity, validPayload().replace(
                ",\"purpose\":\"orion-access\"", ""));

        assertThat(service.verify(token))
                .isEqualTo(JwtAccessTokenService.VerificationResult.failure(
                        "JWT purpose is invalid"));
    }

    @Test
    void rejectsTokenWithoutValidTokenId() throws Exception {
        TestIdentity identity = single("server-signing-v1");
        JwtAccessTokenService service = service(identity, CLOCK);
        String token = signedToken(identity, validPayload().replace(
                "1fc784e3-3238-4279-8627-d8a2e64bc17f", "not-a-uuid"));

        assertThat(service.verify(token))
                .isEqualTo(JwtAccessTokenService.VerificationResult.failure(
                        "JWT token id is invalid"));
    }

    @Test
    void rejectsTokenWithoutIssuedAtOrNotBefore() throws Exception {
        TestIdentity identity = single("server-signing-v1");
        JwtAccessTokenService service = service(identity, CLOCK);
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
        TestIdentity identity = single("server-signing-v1");
        JwtAccessTokenService service = service(identity, CLOCK);
        String token = signedToken(identity, validPayload().replace(
                "\"exp\":1788383400", "\"exp\":1788386401"));

        assertThat(service.verify(token))
                .isEqualTo(JwtAccessTokenService.VerificationResult.failure(
                        "JWT lifetime exceeds 3600 seconds"));
    }

    @Test
    void rejectsTokenThatExpiresBeforeItBecomesActive() throws Exception {
        TestIdentity identity = single("server-signing-v1");
        JwtAccessTokenService service = service(identity, CLOCK);
        String token = signedToken(identity, validPayload()
                .replace("\"nbf\":1788382800", "\"nbf\":1788383500"));

        assertThat(service.verify(token))
                .isEqualTo(JwtAccessTokenService.VerificationResult.failure(
                        "JWT time range is invalid"));
    }

    @Test
    void returnsTokenIdInVerifiedIdentity() throws Exception {
        JwtAccessTokenService service = service(
                single("server-signing-v1"), CLOCK);
        JwtAccessTokenService.IssuedToken token = service.issue("alice", 600, null);

        JwtAccessTokenService.VerificationResult result = service.verify(token.value());

        assertThat(result).isInstanceOf(JwtAccessTokenService.VerificationResult.Success.class);
        JwtAccessTokenService.VerificationResult.Success success =
                (JwtAccessTokenService.VerificationResult.Success) result;
        assertThat(success.tokenId())
                .isEqualTo(stringClaim(payload(token.value()), "jti"));
    }

    @Test
    void usesActiveMaterialAliasAsKid() throws Exception {
        TestIdentity identity = single("server-signing-v2");
        JwtAccessTokenService service = service(identity, CLOCK);

        JwtAccessTokenService.IssuedToken token = service.issue("alice", 600, null);

        assertThat(header(token.value())).contains("\"kid\":\"server-signing-v2\"");
        assertVerified(service, token.value(), "alice", null);
    }

    @Test
    void escapesConfiguredAliasInKid() throws Exception {
        String alias = "server-\"signing\\v2";
        JwtAccessTokenService service = service(single(alias), CLOCK);

        JwtAccessTokenService.IssuedToken token = service.issue("alice", 600, null);

        assertThat(header(token.value())).contains("\"kid\":\"server-\\\"signing\\\\v2\"");
        assertVerified(service, token.value(), "alice", null);
    }

    @Test
    void stagesActivatesAndRetiresSigningKeysWithoutDeletingStoredMaterial() throws Exception {
        KeyPair oldKey = rsaKeyPair();
        KeyPair newKey = rsaKeyPair();
        TestIdentity identity = new TestIdentity("server-signing-v1", Map.of("server-signing-v1", oldKey));
        JwtAccessTokenService oldService = service(identity, CLOCK);
        String oldToken = oldService.issue("alice", 600, null).value();
        KeyMaterialDescriptor next = descriptor("server-signing-v2", 2);
        identity.material.withModification(modification -> {
            modification.importKey(next, newKey);
            modification.apply();
        });

        String stagedToken = oldService.issue("alice", 600, null).value();
        assertThat(header(stagedToken)).contains("\"kid\":\"server-signing-v1\"");
        assertVerified(oldService, stagedToken, "alice", null);
        JwtAccessTokenService rotatedService = new JwtAccessTokenService(identity.material,
                new SigningMaterialSet(next, List.of(identity.signing.active())), CLOCK);
        String newToken = rotatedService.issue("alice", 600, null).value();

        assertVerified(rotatedService, oldToken, "alice", null);
        assertThat(header(newToken)).contains("\"kid\":\"server-signing-v2\"");
        assertVerified(rotatedService, newToken, "alice", null);
        assertThat(oldService.verify(newToken)).isEqualTo(
                JwtAccessTokenService.VerificationResult.failure("JWT signing key is unknown"));

        JwtAccessTokenService retired = new JwtAccessTokenService(identity.material,
                new SigningMaterialSet(next, List.of()), CLOCK);
        assertThat(retired.verify(oldToken)).isEqualTo(
                JwtAccessTokenService.VerificationResult.failure("JWT signing key is unknown"));
        assertVerified(retired, newToken, "alice", null);
        assertThat(identity.material.contains(identity.signing.active())).isTrue();
    }

    @Test
    void rejectsTokenWhoseExactKidIsNotConfigured() throws Exception {
        TestIdentity unknown = single("unknown-signing");
        String token = service(unknown, CLOCK).issue("alice", 600, null).value();
        JwtAccessTokenService configured = service(
                single("server-signing-v1"), CLOCK);

        assertThat(configured.verify(token))
                .isEqualTo(JwtAccessTokenService.VerificationResult.failure(
                        "JWT signing key is unknown"));
    }

    @Test
    void roundTripsOptionalAuthenticationGeneration() throws Exception {
        JwtAccessTokenService service = service(
                single("server-signing-v1"), CLOCK);

        JwtAccessTokenService.IssuedToken token = service.issue("root", 600, "generation-1");

        assertThat(payload(token.value())).contains("\"orion_auth_generation\":\"generation-1\"");
        assertVerified(service, token.value(), "root", "generation-1");
    }

    @Test
    void claimFreeTokensRemainCompatible() throws Exception {
        JwtAccessTokenService service = service(
                single("server-signing-v1"), CLOCK);

        JwtAccessTokenService.IssuedToken token = service.issue("alice", 600, null);

        assertThat(payload(token.value())).doesNotContain("orion_auth_generation");
        assertVerified(service, token.value(), "alice", null);
    }

    @Test
    void rejectsMalformedAuthenticationGenerationClaim() throws Exception {
        TestIdentity identity = single("server-signing-v1");
        JwtAccessTokenService service = service(identity, CLOCK);
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
                .isEqualTo(new JwtAccessTokenService.VerificationResult.Success(
                        subject,
                        authenticationGeneration,
                        stringClaim(payload(token), "jti"),
                        null));
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

    private static JwtAccessTokenService service(TestIdentity identity, Clock clock) {
        return new JwtAccessTokenService(identity.material, identity.signing, clock);
    }

    private TestIdentity single(String alias) throws Exception {
        return new TestIdentity(alias, Map.of(alias, rsaKeyPair()));
    }

    private final class TestIdentity {
        private final OrionKeyMaterial material;
        private final SigningMaterialSet signing;

        private TestIdentity(String activeKeyId, Map<String, KeyPair> keys) throws Exception {
            try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12("test-password".toCharArray())) {
                material = OrionKeyMaterial.open(new InMemoryKeyMaterialContentStore(), options);
            }
            materials.add(material);
            List<KeyMaterialDescriptor> retained = new ArrayList<>();
            KeyMaterialDescriptor active = descriptor(activeKeyId, keys.size());
            material.withModification(modification -> {
                for (Map.Entry<String, KeyPair> entry : keys.entrySet()) {
                    KeyMaterialDescriptor descriptor = entry.getKey().equals(activeKeyId)
                            ? active : descriptor(entry.getKey(), 1);
                    if (!descriptor.equals(active)) retained.add(descriptor);
                    modification.importKey(descriptor, entry.getValue());
                }
                modification.apply();
            });
            signing = new SigningMaterialSet(active, retained);
        }

        private String activeKeyId() {
            return signing.active().alias();
        }

        private byte[] sign(byte[] payload) throws GeneralSecurityException {
            return material.sign(signing.active(), payload);
        }
    }

    private static KeyMaterialDescriptor descriptor(String alias, int version) {
        return new KeyMaterialDescriptor(alias, Set.of(KeyMaterialPurpose.SERVER_SIGNING),
                KeyMaterialAlgorithm.RSA, version);
    }
}
