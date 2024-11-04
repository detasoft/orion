package pro.deta.orion.lifecycle.listener;

import lombok.Getter;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;
import pro.deta.orion.util.OrionUtils;

import java.io.IOException;
import java.util.Comparator;
import java.util.concurrent.Callable;

import static pro.deta.orion.lifecycle.listener.RegisteredListenerResult.DEFAULT_WAIT_FOR_COMPLETION_TIMEOUT_IN_SEC;

@Getter
public class RegisteredListener {

    public static final Comparator<RegisteredListener> COMPARATOR = Comparator
            .comparing(RegisteredListener::getPriority)
            .thenComparing(RegisteredListener::getInitiator);

    private final ApplicationState state;
    private final Callable<OrionStageCallResult> call;
    private final String initiator;
    private int priority;
    private int waitForCompletionSecs;

    public RegisteredListener(ApplicationState state, Callable<OrionStageCallResult> call) {
        this.state = state;
        this.call = call;
        this.initiator = OrionUtils.initiatorOf(call.getClass());
    }

    public RegisteredListener priority(int priority) {
        this.priority = priority;
        return this;
    }

    public RegisteredListener waitForCompletionSecs(int waitForCompletionSecs) {
        this.waitForCompletionSecs = waitForCompletionSecs;
        return this;
    }

    public RegisteredListener waitForCompletion() {
        this.waitForCompletionSecs = DEFAULT_WAIT_FOR_COMPLETION_TIMEOUT_IN_SEC;
        return this;
    }

    @Override
    public String toString() {
        return "RegisteredListener{" +
                "state=" + state +
                ", priority=" + priority +
                ", waitForCompletion=" + waitForCompletionSecs +
                ", initiator='" + initiator + '\'' +
                '}';
    }

    public Appendable format(Appendable appendable) {
        try {
            return appendable.append("state=").append(String.valueOf(getState()))
                    .append(", priority=").append(String.format("%2d",getPriority()))
                    .append(", waitForCompletion=").append(String.format("%2d",getWaitForCompletionSecs()))
                    .append(", initiator='").append(getInitiator());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
