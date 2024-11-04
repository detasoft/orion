package pro.deta.orion.util;

import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.function.Consumer;

public sealed interface Result<T> {

    default boolean isFailure() {
        return this instanceof Failure;
    }

    record Success<T>(T value) implements Result<T> {
        @Override
        public Result<T> onSuccess(Consumer<T> consumer) {
            consumer.accept(value);
            return this;
        }

        @Override
        public Result<T> onFailure(Consumer<Failure<T>> consumer) {
            return this;
        }
    }

    Result<T> onSuccess(Consumer<T> consumer);

    Result<T> onFailure(Consumer<Failure<T>> consumer);

    default Result<T> failOnFailure(String message) {
        valueOrFailure(message);
        return this;
    }

    default T valueOrFailure(String message) {
        switch (this) {
            case Result.Failure<T> v -> {
                Logger.log.error(message+ " {}", v.message, v.throwable);
                throw new IllegalStateException(message, v.throwable);
            }
            case Result.Success<T> v -> {
                return v.value;
            }
        }
    }

    default T valueOrWarning(String message, Object... args) {
        switch (this) {
            case Result.Failure<T> v -> {
                Object[] newArray = Arrays.copyOf(args, args.length + 2);
                newArray[args.length] = v.message;
                newArray[args.length+1] = v.throwable;
                Logger.log.warn(message+ " {}", newArray);
            }
            case Result.Success<T> v -> {
                return v.value;
            }
        }
        return null;
    }

    record Failure<T>(Result.FailureCode code, String message, Throwable throwable) implements Result<T> {
        public Failure(Result.FailureCode code) {
            this(code, null, null);
        }
        public Failure(Result.FailureCode code, String message) {
            this(code, message, null);
        }

        public Failure(Failure<?> f) {
            this(f.code, f.message, f.throwable);
        }

        public Failure(Result.FailureCode code, Throwable throwable) {
            this(code, null, throwable);
        }

        public String getMessage() {
            if (message == null)
                return throwable.getMessage();
            return message;
        }

        public static <T> Failure<T> generalFailure(String message) {
            Logger.log.error(message);
            return new Failure<>(FailureCode.GENERAL, message);
        }

        public static <T> Failure<T> generalFailure(String message, Throwable throwable) {
            Logger.log.error(message, throwable);
            return new Failure<>(FailureCode.GENERAL, message, throwable);
        }

        @Override
        public Result<T> onSuccess(Consumer<T> consumer) {
            return this;
        }

        @Override
        public Result<T> onFailure(Consumer<Failure<T>> consumer) {
            consumer.accept(this);
            return this;
        }
    }

    enum FailureCode {
        GENERAL,
        CREATION_FAILED,
        FILE_ALREADY_EXISTS,
        NOT_SUPPORTED,
        AUTHENTICATION_FAILED,
        TIMEOUT, FALSE, NOT_FOUND,
        EMPTY,
        NOT_EXISTS,

    }

    @Slf4j
    class Logger{};
}
