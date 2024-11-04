package pro.deta.orion.lifecycle.data;

import lombok.Getter;
import pro.deta.orion.util.OrionUtils;

import java.util.concurrent.Future;

public class OrionStageCallResultFuture {
    private final String initiator;

    @Getter
    private final Future<?> future;

    public OrionStageCallResultFuture(Future<?> future, Class clsInitiator) {
        this.initiator = OrionUtils.initiatorOf(clsInitiator);
        this.future = future;
    }

    @Override
    public String toString() {
        return "OrionStageCallResultFuture{" +
                "state=" + future.state() +
                ", initiator='" + initiator + '\'' +
                "future=" + future +
                '}';
    }
}
