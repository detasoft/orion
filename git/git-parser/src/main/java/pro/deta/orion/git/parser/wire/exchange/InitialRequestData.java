package pro.deta.orion.git.parser.wire.exchange;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class InitialRequestData {
    private static final String VERSION_PARAMETER = "version";

    private final InitialRequestService service;
    private final String repositoryPath;
    private final String host;
    private final Map<String, String> parameters;
    private final List<String> protocolParameters;

    public InitialRequestData(
            InitialRequestService service,
            String repositoryPath,
            String host,
            Map<String, String> parameters) {
        this(service, repositoryPath, host, parameters, parameterList(parameters));
    }

    public InitialRequestData(
            InitialRequestService service,
            String repositoryPath,
            String host,
            Map<String, String> parameters,
            List<String> protocolParameters) {
        this.service = service;
        this.repositoryPath = repositoryPath;
        this.host = host;
        this.parameters = Collections.unmodifiableMap(
                new LinkedHashMap<>(parameters));
        this.protocolParameters = List.copyOf(protocolParameters);
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

    public List<String> getProtocolParameters() {
        return protocolParameters;
    }

    private static List<String> parameterList(Map<String, String> parameters) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            result.add(entry.getKey() + "=" + entry.getValue());
        }
        return List.copyOf(result);
    }

    public Optional<ProtocolVersion> getProtocolVersion() {
        String version = parameters.get(VERSION_PARAMETER);
        if (version == null) {
            return Optional.empty();
        }
        return Optional.of(ProtocolVersion.fromWireValue(version));
    }

    public enum ProtocolVersion {
        V0("0"),
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
            throw new IllegalArgumentException(
                    "Unsupported Git protocol version '" + wireValue + "'");
        }
    }
}
