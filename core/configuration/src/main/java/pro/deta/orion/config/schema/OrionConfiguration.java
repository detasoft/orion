package pro.deta.orion.config.schema;

import lombok.Data;
import lombok.RequiredArgsConstructor;

import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Data
@Singleton
public class OrionConfiguration {
    private BootstrapConfig bootstrap = new BootstrapConfig();
    private StorageConfig storage = new StorageConfig();
    private AppTransport transport = new AppTransport();

    @Data
    public static class BootstrapConfig {
        private String baseDir = "orion";
        private String workDir = "work";
        private int threadPoolSize = 10;
        private JGitConfig jgit = new JGitConfig();
        private BootstrapAccessControlConfig accessControl = new BootstrapAccessControlConfig();
    }

    @Data
    public static class BootstrapAccessControlConfig {
        private String location = "local:orion";
        private String branch = "master";
        private List<String> paths = new ArrayList<>(List.of("orion.xml"));
        private boolean createDefaultIfMissing = true;
        private Map<String, String> auth = new LinkedHashMap<>();

        public String primaryPath() {
            if (paths == null || paths.isEmpty()) {
                throw new IllegalStateException("At least one ACL path must be configured");
            }
            return paths.getFirst();
        }
    }

    @Data
    public static class StorageConfig {
        private String location = "file:orion/repos";
        private boolean createOnPush = true;
        private Map<String, String> auth = new LinkedHashMap<>();
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
    public static class AppTransport {
        private String defaultAddress = null;
        private GitTransportConfig git = new GitTransportConfig(null, 9418);
        private SshTransportConfig ssh = new SshTransportConfig(null, 8022);
        private HttpTransportConfig http = new HttpTransportConfig(null, 8000);
        private HttpsTransportConfig https = new HttpsTransportConfig(null, 8443);
    }
}
