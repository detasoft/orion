package pro.deta.orion.config;

import lombok.Data;

public class AppConfigContext {
    private final AppConfiguration globalConfiguration;

    public AppConfigContext(AppConfiguration globalConfiguration) {
        this.globalConfiguration = globalConfiguration;
    }

    @Data
    public static class AppConfiguration {
        private GitServer git = new GitServer();
        private SettingsConfig settings = new SettingsConfig();
        private AppTransport transports = new AppTransport();
        private String baseDir = "orion";
    }

    @Data
    public static class AppTransport {
        private GitTransportConfig git;
        private SshTransportConfig ssh;
        private HttpTransportConfig http;
    }

    @Data
    public static class GitServer {
        private String storagePath = "repos";
        private int threadPoolSize = 10;
    }

    @Data
    public static class SettingsConfig {
        // "https://github.com/bade7n/orion.git";
//         "local://orion.git";
        private String repositoryUrl = "ssh://git@github.com:bade7n/orion.git";
        private String username;
        private String password;
        private String sshKey;

        private String workingDir = "work";
        private String branchName = "master";
        private String settingsFile = "orion.xml";
    }
}