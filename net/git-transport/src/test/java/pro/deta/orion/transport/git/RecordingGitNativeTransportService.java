package pro.deta.orion.transport.git;

import pro.deta.orion.config.schema.GitTransportConfig;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;

final class RecordingGitNativeTransportService extends GitNativeTransportService {
    private int startCalls;
    private int stopCalls;
    private RuntimeException startFailure;

    RecordingGitNativeTransportService() {
        this(true);
    }

    RecordingGitNativeTransportService(boolean enabled) {
        super(config(enabled), null, null, 5_000);
    }

    private static GitTransportConfig config(boolean enabled) {
        GitTransportConfig config = new GitTransportConfig("127.0.0.1", 0);
        config.setEnabled(enabled);
        return config;
    }

    @Override
    public OrionStageCallResult onStart() {
        startCalls++;
        if (startFailure != null) {
            throw startFailure;
        }
        return null;
    }

    @Override
    public OrionStageCallResult onStop() {
        stopCalls++;
        return null;
    }

    void failStartWith(RuntimeException failure) {
        startFailure = failure;
    }

    int startCalls() {
        return startCalls;
    }

    int stopCalls() {
        return stopCalls;
    }
}
