package pro.deta.orion.git.client;

public enum GitProtocolService {
    UPLOAD_PACK("git-upload-pack"),
    RECEIVE_PACK("git-receive-pack");

    private final String command;

    GitProtocolService(String command) {
        this.command = command;
    }

    public String command() {
        return command;
    }
}
