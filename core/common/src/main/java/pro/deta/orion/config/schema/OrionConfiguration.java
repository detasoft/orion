package pro.deta.orion.config.schema;

import lombok.Data;
import lombok.RequiredArgsConstructor;

import javax.inject.Singleton;

@RequiredArgsConstructor
@Data
@Singleton
public class OrionConfiguration {
    private InternalGitServer git = new InternalGitServer();
    private AccessControlConfig accessControl = new AccessControlConfig();
    private AppTransport transports = new AppTransport();
    private String baseDir = "orion";
    private String workDir = "work";
    private int threadPoolSize = 10;

    @Data
    public static class InternalGitServer {
        private String storagePath = "repos";
    }

    @Data
    public static class AccessControlConfig {
        // "https://github.com/bade7n/orion.git"
        // "ssh://git@jump.deta.pro:deta/orion_runtime.git"
        // "local://orion.git"
        // "file://pathToDir/
        private ACLStorageType type = ACLStorageType.GIT;
        private String url = "local+ssh://orion";
        private String username;
        // could be password, or path to keyfile depends on the url / ignored for local access
        private String credential;

        // local access via ssh or git (no auth) protocol
        private String settingsFileName = "orion.xml";
        private String branch = "master";
    }

    public enum ACLStorageType {
        GIT, JDBC, LOCAL
    }

    @Data
    public static class AppTransport {
        private String defaultAddress = null;
        private GitTransportConfig git = new GitTransportConfig(null, 9418);
        private SshTransportConfig ssh = new SshTransportConfig(null, 8022);
        private HttpTransportConfig http = new HttpTransportConfig(null, 8000);
        private HttpsTransportConfig https = new HttpsTransportConfig(null, 8443);
    }
}