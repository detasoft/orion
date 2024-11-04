package pro.deta.orion.comm.common;

import lombok.Data;
import lombok.ToString;

import java.net.InetSocketAddress;
import java.util.Objects;

@Data
@ToString
public class DtlsSessionEndpoint<T> {
    private final T remote;
    @ToString.Exclude
    private final int remoteHashCode;
    private final T local;
    @ToString.Exclude
    private final int localHashCode;

    public DtlsSessionEndpoint(T remote, T local) {
        this(remote, local, remote.hashCode(), local.hashCode());
    }
    public DtlsSessionEndpoint(T remote, T local, int remoteHashCode, int localHashCode) {
        this.local = local;
        this.remote = remote;
        this.remoteHashCode = remoteHashCode;
        this.localHashCode = localHashCode;
        if (remote instanceof InetSocketAddress isa) {
            assert !isa.isUnresolved(); // force only resolved addresses as used as parametrized types
        }
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof DtlsSessionEndpoint that)) return false;
        return Objects.equals(remote, that.remote);
    }

    @Override
    public int hashCode() {
        return (31 * remoteHashCode) + localHashCode;
    }
}
