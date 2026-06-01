package pro.deta.orion.transport.git;

import pro.deta.orion.config.schema.GitTransportConfig;

import java.util.concurrent.CountDownLatch;

final class RecordingGitNativeTransportService extends GitNativeTransportService {
    private int startCalls;
    private int stopCalls;
    private RuntimeException startFailure;
    private CountDownLatch startEntering;
    private CountDownLatch startGate;

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
    public void onStart() {
        startCalls++;
        if (startEntering != null) {
            startEntering.countDown();
        }
        if (startGate != null) {
            try {
                startGate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (startFailure != null) {
            throw startFailure;
        }
    }

    @Override
    public void onStop() {
        stopCalls++;
    }

    void failStartWith(RuntimeException failure) {
        startFailure = failure;
    }

    void blockStartWith(CountDownLatch entering, CountDownLatch gate) {
        this.startEntering = entering;
        this.startGate = gate;
    }

    int startCalls() {
        return startCalls;
    }

    int stopCalls() {
        return stopCalls;
    }
}
