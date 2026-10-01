package pro.deta.orion.agent.protocol;

import java.util.Objects;

public record AgentAuthentication(
        AgentGeneration generation,
        AgentLaunchId launchId,
        Kind kind,
        ProtocolBytes credential
) {
    public static final int MIN_CREDENTIAL_BYTES = 32;
    public static final int MAX_CREDENTIAL_BYTES = 512;

    public AgentAuthentication {
        Objects.requireNonNull(generation, "generation");
        Objects.requireNonNull(launchId, "launchId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(credential, "credential");
        ProtocolValidation.byteLength(
                credential.size(), MIN_CREDENTIAL_BYTES, MAX_CREDENTIAL_BYTES, "authentication credential");
    }

    public enum Kind {
        LAUNCH_PERMIT(1),
        RECONNECT_TOKEN(2);

        private final int wireCode;

        Kind(int wireCode) {
            this.wireCode = wireCode;
        }

        public int wireCode() {
            return wireCode;
        }

        public static Kind fromWireCode(int wireCode) {
            for (Kind value : values()) {
                if (value.wireCode == wireCode) {
                    return value;
                }
            }
            return null;
        }
    }
}
