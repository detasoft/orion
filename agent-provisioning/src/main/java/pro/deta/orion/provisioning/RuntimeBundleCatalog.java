package pro.deta.orion.provisioning;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RuntimeBundleCatalog {
    private final Map<BundleKey, RemoteRuntimeBundle> bundles;

    public RuntimeBundleCatalog(List<RemoteRuntimeBundle> bundles) {
        if (bundles == null || bundles.isEmpty()) {
            throw new IllegalArgumentException("Runtime bundle catalog must not be empty");
        }
        Map<BundleKey, RemoteRuntimeBundle> indexed = new LinkedHashMap<>();
        for (RemoteRuntimeBundle bundle : bundles) {
            if (bundle == null) {
                throw new IllegalArgumentException("Runtime bundle catalog must not contain null");
            }
            BundleKey key = new BundleKey(bundle.platform(), bundle.version());
            if (indexed.put(key, bundle) != null) {
                throw new IllegalArgumentException("Runtime bundle catalog contains a duplicate bundle");
            }
        }
        this.bundles = Map.copyOf(indexed);
    }

    public RemoteRuntimeBundle select(RemotePlatform platform, String version) {
        RemoteRuntimeBundle bundle = bundles.get(new BundleKey(platform, version));
        if (bundle == null) {
            throw new IllegalArgumentException(
                    "No runtime bundle for remote platform and version: " + platform + "/" + version);
        }
        return bundle;
    }

    private record BundleKey(RemotePlatform platform, String version) {
        private BundleKey {
            if (platform == null || version == null) {
                throw new IllegalArgumentException("Runtime bundle selection must not be null");
            }
        }
    }
}
