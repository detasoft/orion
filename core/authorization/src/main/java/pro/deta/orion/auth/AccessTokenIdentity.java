package pro.deta.orion.auth;

public record AccessTokenIdentity(String tokenId, String subject) {
    public AccessTokenIdentity {
        if (tokenId == null || tokenId.isBlank()) {
            throw new IllegalArgumentException("Token id is required");
        }
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("Token subject is required");
        }
    }
}
