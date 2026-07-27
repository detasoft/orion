package pro.deta.orion.continuation;

import java.util.Objects;

/**
 * Describes what the continuation runtime should do after one continuation step.
 */
public sealed interface ContinuationFlow<I>
        permits ContinuationFlow.Await, ContinuationFlow.Continue, ContinuationFlow.Transition {

    static <I> ContinuationFlow<I> continueFlow() {
        return new Continue<>();
    }

    static <I> ContinuationFlow<I> await() {
        return new Await<>();
    }

    static <I> Transition<I> transition(Continuation<I> next) {
        return new Transition<>(next);
    }

    record Continue<I>() implements ContinuationFlow<I> {
    }

    record Await<I>() implements ContinuationFlow<I> {
    }

    record Transition<I>(Continuation<I> next) implements ContinuationFlow<I> {
        public Transition {
            Objects.requireNonNull(next, "next");
        }
    }
}
