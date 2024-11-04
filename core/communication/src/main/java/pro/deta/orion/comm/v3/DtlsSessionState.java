package pro.deta.orion.comm.v3;

import pro.deta.orion.comm.util.RecentTimestampedValueBuffer;

public class DtlsSessionState {
    private RecentTimestampedValueBuffer<DtlsSessionState.Status> recentStatus = new RecentTimestampedValueBuffer<Status>(5, Status.INITIALIZING);
    private volatile long lastAccessInMillis = System.currentTimeMillis();

    public long getLastAccessTime() {
        return lastAccessInMillis;
    }

    public void updateLastAccessTime() {
        this.lastAccessInMillis = System.currentTimeMillis();
    }

    public static enum Status {
        INITIALIZING,

    }
}