package pro.deta.orion.agentd.session;

import java.util.Arrays;
import java.util.Objects;

@FunctionalInterface
public interface ControlTransport {
    Exchange exchange(ControlEndpoint endpoint, byte[] request, OperationDeadline deadline);

    sealed interface Exchange {
        record Response(byte[] frame) implements Exchange {
            public Response {
                frame = Arrays.copyOf(Objects.requireNonNull(frame, "frame"), frame.length);
            }

            @Override
            public byte[] frame() {
                return Arrays.copyOf(frame, frame.length);
            }
        }

        record Failed(ControlResult.FailureKind kind, boolean mayHaveDelivered, String detail)
                implements Exchange {
            public Failed {
                Objects.requireNonNull(kind, "kind");
                Objects.requireNonNull(detail, "detail");
            }
        }
    }
}
