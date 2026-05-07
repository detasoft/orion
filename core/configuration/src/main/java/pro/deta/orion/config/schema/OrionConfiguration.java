package pro.deta.orion.config.schema;

import lombok.Data;
import lombok.RequiredArgsConstructor;

import javax.inject.Singleton;
import java.util.LinkedHashMap;
import java.util.Map;

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
        private JGitConfig jgit = new JGitConfig();
    }

    @Data
    public static class JGitConfig {
        private String hostname = "localhost";
        private String osName = "Linux";
        private String defaultCharset = "UTF-8";
        private String timezone = "UTC";
        private String locale = "und";
        private Map<String, String> properties = new LinkedHashMap<>();
        private Map<String, String> environment = new LinkedHashMap<>();
        private Map<String, String> systemConfig = new LinkedHashMap<>();
        private Map<String, String> userConfig = new LinkedHashMap<>();
        private Map<String, String> jgitConfig = new LinkedHashMap<>();
    }

    @Data
    public static class AccessControlConfig {
        // "https://github.com/vladilm/orion.git"
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
