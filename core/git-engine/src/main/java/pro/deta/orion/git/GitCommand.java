package pro.deta.orion.git;

import lombok.Data;

import java.util.Properties;

@Data
public class GitCommand {
    private final Command command;
    private final String locator;
    private final Properties properties = new Properties();

    public void addProperty(String name, String value) {
        properties.put(name, value);
    }

    public Properties getProperties() {
        Properties p = new Properties();
        p.putAll(properties);
        return p;
    }

    public String getRepositoryName() {
        String repositoryName = locator.replaceFirst("\\.git$", "");
        repositoryName = repositoryName.replaceFirst("^\\/", "");
        return repositoryName;
    }

    public boolean isRead() {
        return command == Command.RECEIVE;
    }

    public boolean isWrite() {
        return command == Command.UPLOAD;
    }

    enum Command {
        UPLOAD, RECEIVE, UNKNOWN;

        public static Command parseFrom(String s) {
            if ("git-upload-pack".equalsIgnoreCase(s))
                return UPLOAD;
            if ("git-receive-pack".equalsIgnoreCase(s))
                return RECEIVE;
            return UNKNOWN;
        }

        public boolean isUnknown() {
            return this == UNKNOWN;
        }
    }
}
