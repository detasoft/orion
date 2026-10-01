package pro.deta.orion.agentd.session;

import java.util.Objects;

@FunctionalInterface
public interface ControlTransportFactory {
    Selection select(ControlEndpoint endpoint);

    static ControlTransportFactory nativeTransports() {
        ControlTransport unix = new UnixDomainControlTransport();
        return endpoint -> switch (endpoint.transport()) {
            case UNIX_DOMAIN_SOCKET -> new Selection.Available(unix);
            case NAMED_PIPE -> new Selection.Unsupported(
                    "named-pipe control awaits the native Windows session host");
        };
    }

    sealed interface Selection {
        record Available(ControlTransport transport) implements Selection {
            public Available {
                Objects.requireNonNull(transport, "transport");
            }
        }

        record Unsupported(String detail) implements Selection {
            public Unsupported {
                Objects.requireNonNull(detail, "detail");
            }
        }
    }
}
