package pro.deta.orion.git.client;

public enum GitClientService {
    UPLOAD_PACK("git-upload-pack"),
    RECEIVE_PACK("git-receive-pack");

    private final String command;

    GitClientService(String command) {
        this.command = command;
    }

    public String command() {
        return command;
    }
}
