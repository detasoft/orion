package pro.deta.orion.lifecycle.data;

import lombok.Data;
import pro.deta.orion.internal.OrionExecutor;

import java.util.ArrayList;
import java.util.List;

@Data
public class OrionStageCallResult {
    public static final int DEFAULT_WAIT_FOR_COMPLETION_TIMEOUT_IN_SEC = 5;
    public static final OrionStageCallResult EMPTY = new OrionStageCallResult(0);

    private final List<OrionStageCallResultFuture> futuresToWait = new ArrayList<>();
    private final int waitForCompletionSecs;

    public OrionStageCallResult(int waitForCompletionSecs) {
        this.waitForCompletionSecs = waitForCompletionSecs;
    }

    public static OrionStageCallResult defaultWithWait() {
        return new OrionStageCallResult(DEFAULT_WAIT_FOR_COMPLETION_TIMEOUT_IN_SEC);
    }

    public boolean neededToWait() {
        return waitForCompletionSecs > 0;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("OrionStageCallResult{waitForCompletionSecs=" + waitForCompletionSecs + ", futures=[\n");
        for (OrionStageCallResultFuture future : futuresToWait) {
            builder.append("\t\t");
            builder.append(future).append("\n");
        }
        return builder.append("\t\t]").toString();
    }

    public void submit(OrionExecutor orionExecutor, Runnable consumer) {
        futuresToWait.add(new OrionStageCallResultFuture(orionExecutor.submit(consumer), consumer.getClass()));
    }
}
