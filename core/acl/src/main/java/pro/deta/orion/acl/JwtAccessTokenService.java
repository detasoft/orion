package pro.deta.orion.acl;

import pro.deta.orion.crypto.PublicKeysProvider;
import pro.deta.orion.crypto.ServerKeySigner;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.util.Base64;
import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class JwtAccessTokenService {
    private static final String HEADER_ALGORITHM = "RS256";
    private static final String JWT_TYPE = "JWT";
    private static final String ISSUER = "orion";
    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();
    private static final Pattern STRING_CLAIM = Pattern.compile("\"%s\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern LONG_CLAIM = Pattern.compile("\"%s\"\\s*:\\s*(\\d+)");

    private final ServerKeySigner serverKeySigner;
    private final PublicKeysProvider publicKeysProvider;
    private final Clock clock;

    JwtAccessTokenService(ServerKeySigner serverKeySigner, PublicKeysProvider publicKeysProvider) {
        this(serverKeySigner, publicKeysProvider, Clock.systemUTC());
    }

    JwtAccessTokenService(ServerKeySigner serverKeySigner, PublicKeysProvider publicKeysProvider, Clock clock) {
        this.serverKeySigner = serverKeySigner;
        this.publicKeysProvider = publicKeysProvider;
        this.clock = clock;
    }

    IssuedToken issue(String subject, long expiresInSeconds) {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("Token subject is required");
        }
        if (expiresInSeconds <= 0) {
            throw new IllegalArgumentException("Token expiration must be positive");
        }

        ServerKeySigner.SigningKey signingKey = serverKeySigner.rsaSha256SigningKey();

        long issuedAt = clock.instant().getEpochSecond();
        long expiresAt = Math.addExact(issuedAt, expiresInSeconds);
        String header = "{\"alg\":\"%s\",\"typ\":\"%s\",\"kid\":\"%s\"}".formatted(
                HEADER_ALGORITHM,
                JWT_TYPE,
                keyId(signingKey.publicKey()));
        String payload = "{\"iss\":\"%s\",\"sub\":%s,\"iat\":%d,\"exp\":%d}".formatted(
                ISSUER,
                jsonString(subject),
                issuedAt,
                expiresAt);
        String signingInput = base64Url(header.getBytes(StandardCharsets.UTF_8))
                + "."
                + base64Url(payload.getBytes(StandardCharsets.UTF_8));
        byte[] signature = signingKey.sign(signingInput.getBytes(StandardCharsets.US_ASCII));
        String token = signingInput + "." + base64Url(signature);
        return new IssuedToken(token, expiresAt);
    }

    VerificationResult verify(String token) {
        if (token == null || token.isBlank()) {
            return VerificationResult.failure("token is required");
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            return VerificationResult.failure("invalid JWT format");
        }

        String header;
        String payload;
        byte[] signature;
        try {
            header = new String(BASE64_URL_DECODER.decode(parts[0]), StandardCharsets.UTF_8);
            payload = new String(BASE64_URL_DECODER.decode(parts[1]), StandardCharsets.UTF_8);
            signature = BASE64_URL_DECODER.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            return VerificationResult.failure("invalid JWT encoding");
        }

        String algorithm = stringClaim(header, "alg");
        String keyId = stringClaim(header, "kid");
        if (!HEADER_ALGORITHM.equals(algorithm)) {
            return VerificationResult.failure("unsupported JWT algorithm");
        }
        if (keyId == null || keyId.isBlank()) {
            return VerificationResult.failure("JWT key id is required");
        }

        PublicKey publicKey = publicKeyById(keyId);
        if (publicKey == null) {
            return VerificationResult.failure("JWT signing key is unknown");
        }
        if (!verify(publicKey, parts[0] + "." + parts[1], signature)) {
            return VerificationResult.failure("JWT signature is invalid");
        }

        String issuer = stringClaim(payload, "iss");
        if (!ISSUER.equals(issuer)) {
            return VerificationResult.failure("JWT issuer is invalid");
        }
        String subject = stringClaim(payload, "sub");
        Long expiresAt = longClaim(payload, "exp");
        if (subject == null || subject.isBlank()) {
            return VerificationResult.failure("JWT subject is required");
        }
        if (expiresAt == null) {
            return VerificationResult.failure("JWT expiration is required");
        }
        if (expiresAt <= clock.instant().getEpochSecond()) {
            return VerificationResult.failure("JWT is expired");
        }
        return VerificationResult.success(subject);
    }

    private PublicKey publicKeyById(String keyId) {
        Collection<PublicKey> publicKeys = publicKeysProvider.getPublicKeys();
        if (publicKeys == null) {
            return null;
        }
        for (PublicKey publicKey : publicKeys) {
            if (publicKey != null && keyId.equals(keyId(publicKey))) {
                return publicKey;
            }
        }
        return null;
    }

    private String keyId(PublicKey publicKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return base64Url(digest.digest(publicKey.getEncoded()));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot calculate JWT key id", e);
        }
    }

    private boolean verify(PublicKey publicKey, String signingInput, byte[] signatureBytes) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(publicKey);
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signature.verify(signatureBytes);
        } catch (Exception e) {
            return false;
        }
    }

    private String base64Url(byte[] value) {
        return BASE64_URL_ENCODER.encodeToString(value);
    }

    private String jsonString(String value) {
        StringBuilder result = new StringBuilder(value.length() + 2);
        result.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        result.append("\\u%04x".formatted((int) ch));
                    } else {
                        result.append(ch);
                    }
                }
            }
        }
        result.append('"');
        return result.toString();
    }

    private String stringClaim(String json, String claim) {
        Pattern pattern = Pattern.compile(STRING_CLAIM.pattern().formatted(Pattern.quote(claim)));
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            return null;
        }
        return unescapeJsonString(matcher.group(1));
    }

    private Long longClaim(String json, String claim) {
        Pattern pattern = Pattern.compile(LONG_CLAIM.pattern().formatted(Pattern.quote(claim)));
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String unescapeJsonString(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch != '\\') {
                result.append(ch);
                continue;
            }
            if (i + 1 >= value.length()) {
                return value;
            }
            char escaped = value.charAt(++i);
            switch (escaped) {
                case '"' -> result.append('"');
                case '\\' -> result.append('\\');
                case '/' -> result.append('/');
                case 'b' -> result.append('\b');
                case 'f' -> result.append('\f');
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case 'u' -> {
                    if (i + 4 >= value.length()) {
                        return value;
                    }
                    String hex = value.substring(i + 1, i + 5);
                    try {
                        result.append((char) Integer.parseInt(hex, 16));
                    } catch (NumberFormatException e) {
                        return value;
                    }
                    i += 4;
                }
                default -> result.append(escaped);
            }
        }
        return result.toString();
    }

    record IssuedToken(String value, long expiresAtEpochSecond) {
    }

    sealed interface VerificationResult permits VerificationResult.Success, VerificationResult.Failure {
        record Success(String subject) implements VerificationResult {
        }

        record Failure(String reason) implements VerificationResult {
        }

        static VerificationResult success(String subject) {
            return new Success(subject);
        }

        static VerificationResult failure(String reason) {
            return new Failure(reason);
        }
    }
}
