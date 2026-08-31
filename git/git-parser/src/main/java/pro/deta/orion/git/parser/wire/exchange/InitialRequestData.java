package pro.deta.orion.git.parser.wire.exchange;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class InitialRequestData {
    private static final String VERSION_PARAMETER = "version";

    private final InitialRequestService service;
    private final String repositoryPath;
    private final String host;
    private final Map<String, String> parameters;

    public InitialRequestData(
            InitialRequestService service,
            String repositoryPath,
            String host,
            Map<String, String> parameters) {
        this.service = service;
        this.repositoryPath = repositoryPath;
        this.host = host;
        this.parameters = Collections.unmodifiableMap(
                new LinkedHashMap<>(parameters));
    }

    public InitialRequestService getService() {
        return service;
    }

    public String getRepositoryPath() {
        return repositoryPath;
    }

    public String getHost() {
        return host;
    }

    public Map<String, String> getParameters() {
        return parameters;
    }

    public Optional<ProtocolVersion> getProtocolVersion() {
        String version = parameters.get(VERSION_PARAMETER);
        if (version == null) {
            return Optional.empty();
        }
        return Optional.of(ProtocolVersion.fromWireValue(version));
    }

    public enum ProtocolVersion {
        V1("1"),
        V2("2");

        private final String wireValue;

        ProtocolVersion(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }

        private static ProtocolVersion fromWireValue(String wireValue) {
            for (ProtocolVersion version : values()) {
                if (version.wireValue.equals(wireValue)) {
                    return version;
                }
            }
            throw new IllegalArgumentException("Unsupported Git protocol version '" + wireValue + "'");
        }
    }
}
