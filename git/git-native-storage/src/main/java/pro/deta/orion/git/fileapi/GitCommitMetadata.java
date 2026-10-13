package pro.deta.orion.git.fileapi;

import java.util.Objects;

public record GitCommitMetadata(GitCommitAuthor author, String message) {
    public static final GitCommitMetadata EMPTY = new GitCommitMetadata(GitCommitAuthor.EMPTY, "");

    public GitCommitMetadata {
        author = Objects.requireNonNullElse(author, GitCommitAuthor.EMPTY);
        message = message == null || message.isBlank() ? "" : message;
    }
}
