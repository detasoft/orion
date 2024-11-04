package pro.deta.orion.lifecycle;

import pro.deta.orion.ApplicationState;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;
import pro.deta.orion.lifecycle.listener.RegisteredListener;

import java.util.concurrent.Callable;


public interface ApplicationStateListenerRegistrar {

    default RegisteredListener register(ApplicationState state, Callable<OrionStageCallResult> call) {
        return register(new RegisteredListener(state, call));
    }

    RegisteredListener register(RegisteredListener listener);
}
