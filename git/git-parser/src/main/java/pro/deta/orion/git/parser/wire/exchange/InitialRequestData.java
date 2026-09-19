package pro.deta.orion.git.parser.wire.exchange;

import pro.deta.orion.git.parser.v2.data.GitProtocolVersion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record InitialRequestData(InitialRequestService service, String repositoryPath, String host,
                                Map<String, String> parameters, List<String> protocolParameters) {
    private static final String VERSION_PARAMETER = "version";

    public InitialRequestData(
            InitialRequestService service,
            String repositoryPath,
            String host,
            Map<String, String> parameters) {
        this(service, repositoryPath, host, parameters, parameterList(parameters));
    }

    public InitialRequestData {
        parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
        protocolParameters = List.copyOf(protocolParameters);
    }

    private static List<String> parameterList(Map<String, String> parameters) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            result.add(entry.getKey() + "=" + entry.getValue());
        }
        return List.copyOf(result);
    }

    public Optional<GitProtocolVersion> getProtocolVersion() {
        String version = parameters.get(VERSION_PARAMETER);
        if (version == null) {
            return Optional.empty();
        }
        return Optional.of(GitProtocolVersion.fromWireValue(version));
    }
}
