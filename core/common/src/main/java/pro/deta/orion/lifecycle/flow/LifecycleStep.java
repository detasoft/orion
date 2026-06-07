package pro.deta.orion.lifecycle.flow;

import pro.deta.orion.ApplicationState;

import java.util.Objects;

public record LifecycleStep(
        ApplicationState from,
        ApplicationState success,
        ApplicationState failure,
        boolean runRuntime
) {
    public LifecycleStep {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(success, "success");
        Objects.requireNonNull(failure, "failure");
    }

    public static Builder from(ApplicationState from) {
        return new Builder(from);
    }

    public static final class Builder {
        private final ApplicationState from;
        private ApplicationState success;
        private ApplicationState failure = ApplicationState.FAILED;
        private boolean runRuntime = true;

        private Builder(ApplicationState from) {
            this.from = from;
        }

        public Builder to(ApplicationState success) {
            this.success = success;
            return this;
        }

        public Builder onFailure(ApplicationState failure) {
            this.failure = failure;
            return this;
        }

        public Builder transitionOnly() {
            this.runRuntime = false;
            return this;
        }

        public LifecycleStep build() {
            return new LifecycleStep(from, success, failure, runRuntime);
        }
    }
}
