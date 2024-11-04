package pro.deta.orion.lifecycle.listener;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;
import pro.deta.orion.util.Result;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static pro.deta.orion.util.OrionUtils.waitForCompletion;

@Getter
@Slf4j
public class RegisteredListenerResult {
    public static final int DEFAULT_WAIT_FOR_COMPLETION_TIMEOUT_IN_SEC = 5;

    private final RegisteredListener registeredListener;
    private Future<OrionStageCallResult> listenerFuture = null;
    private Result<OrionStageCallResult> listenerResult = null;

    public void waitListener() {
        listenerResult = waitForCompletion(listenerFuture, getWaitForCompletionSecs());
        listenerResult.valueOrWarning("[{}] running stage listener {} block: {}", registeredListener.getState(), registeredListener.getInitiator(), getWaitForCompletionSecs());
    }

    public void waitFeaturesIfNeeded() {
        listenerResult.onSuccess((value) -> {
                if (value != null && value.neededToWait()) {
                    value.getFuturesToWait().forEach(future -> {
                        waitForCompletion(future.getFuture(), value.getWaitForCompletionSecs());
                    });
                }
            }
        );
    }


    public int getWaitForCompletionSecs() {
        return registeredListener.getWaitForCompletionSecs() > 0 ? registeredListener.getWaitForCompletionSecs() : 5;
    }

    public boolean neededToWait() {
        return registeredListener.getWaitForCompletionSecs() > 0;
    }

    public RegisteredListenerResult(RegisteredListener registeredListener) {
        this.registeredListener = registeredListener;
    }

    public void format(StringBuilder builder) {
        builder.append("\tRegisteredListener{");
        registeredListener.format(builder);
        builder.append("'}\n");
    }

    public void execute(ScheduledThreadPoolExecutor executor) {
        listenerFuture = executor.submit(wrapInException(registeredListener.getCall()));
    }

    private static Callable<OrionStageCallResult> wrapInException(Callable<OrionStageCallResult> f) {
        Callable<OrionStageCallResult> wrapper = () -> {
            try {
                return f.call();
            } catch (Exception e) {
                log.error("Exception while calling staging future.", e);
                throw new RuntimeException(e);
            }
        };
        return wrapper;
    }

    public void formatStatus(StringBuilder sb) {
        sb.append("\tRegisteredListener{");
        registeredListener.format(sb);
        sb.append(", Result=").append(listenerFuture.state());
        if (listenerResult != null) {
            switch (listenerResult) {
                case Result.Failure<OrionStageCallResult> v -> {
                    sb.append("\t\tError{code=").append(v.code()).append(",message=").append(v.message()).append("}\n");
                }
                case Result.Success<OrionStageCallResult> v -> {
                    if (v.value() != null && v.value().getFuturesToWait() != null && !v.value().getFuturesToWait().isEmpty()) {
                        sb.append("\t\t").append(v.value()).append("\n");
                    }
                }
            }
        } else {
            sb.append(", listenerResult=null");
        }
        sb.append("\t}\n");
    }

    public boolean isSuccess() {
        return listenerFuture.state() == Future.State.SUCCESS;
    }
}
