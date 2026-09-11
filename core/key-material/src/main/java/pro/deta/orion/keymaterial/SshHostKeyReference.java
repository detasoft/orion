package pro.deta.orion.keymaterial;

public record SshHostKeyReference(String alias) {
    public SshHostKeyReference {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("SSH host key alias must not be empty");
        }
    }
}
