package pro.deta.orion.transport.git;

import pro.deta.orion.config.schema.GitTransportConfig;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;

final class RecordingGitNativeTransportService extends GitNativeTransportService {
    private int startCalls;
    private int stopCalls;
    private RuntimeException startFailure;

    RecordingGitNativeTransportService() {
        super(new GitTransportConfig("127.0.0.1", 0), null, null, 5_000);
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
