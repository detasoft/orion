package pro.deta.orion.schema.config;

import lombok.Data;
import lombok.RequiredArgsConstructor;

import jakarta.inject.Singleton;
import java.util.LinkedHashMap;
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
        private BootstrapAccessControlConfig accessControl = new BootstrapAccessControlConfig();
        private KeyMaterialConfig keyMaterial = new KeyMaterialConfig();
    }

    public static class BootstrapAccessControlConfig extends BootstrapConfigurationSourceConfig {
    }

    @Data
    public static class StorageConfig {
        private String location = "file:orion/repos";
        private boolean createOnPush = true;
        private Map<String, String> auth = new LinkedHashMap<>();
    }

    @Data
    public static class AppTransport {
        private String defaultAddress = null;
        private GitTransportConfig git = new GitTransportConfig();
        private SshTransportConfig ssh = new SshTransportConfig(null, 8022);
        private HttpTransportConfig http = new HttpTransportConfig(null, 8000);
        private HttpsTransportConfig https = new HttpsTransportConfig(null, 8443);
    }
}
