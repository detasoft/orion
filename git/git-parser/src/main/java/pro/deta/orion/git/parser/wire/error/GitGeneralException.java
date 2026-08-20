package pro.deta.orion.git.parser.wire.error;

public class GitGeneralException extends Exception {
    private final GitWireError.Kind kind;

    public GitGeneralException(GitWireError.Kind kind) {
        this.kind = kind;
    }

    public String getMessage() {
        return kind.name() + " : " + kind.getMessage();
    }
}
