package pro.deta.orion.keymaterial.bootstrap;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * Opens material and configuration independently, joins them at one barrier,
 * then prepares and publishes one snapshot.
 */
public final class MaterialBootstrapCoordinator<M, C, R> {
    private final MaterialBootstrapLoader<M> materialLoader;
    private final MaterialBootstrapLoader<C> configurationLoader;
    private final MaterialBootstrapCandidateFactory<M, C, R> candidateFactory;
    private final MaterialBootstrapPublisher<R> publisher;

    public MaterialBootstrapCoordinator(
            MaterialBootstrapLoader<M> materialLoader,
            MaterialBootstrapLoader<C> configurationLoader,
            MaterialBootstrapCandidateFactory<M, C, R> candidateFactory,
            MaterialBootstrapPublisher<R> publisher) {
        this.materialLoader = Objects.requireNonNull(materialLoader, "materialLoader");
        this.configurationLoader = Objects.requireNonNull(configurationLoader, "configurationLoader");
        this.candidateFactory = Objects.requireNonNull(candidateFactory, "candidateFactory");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    public CompletionStage<MaterialBootstrapResult<R>> bootstrap() {
        CompletionStage<MaterialBootstrapLoadResult<M>> material = startLoad(
                materialLoader,
                MaterialBootstrapFailure.Source.MATERIAL_STORE);
        CompletionStage<MaterialBootstrapLoadResult<C>> configuration = startLoad(
                configurationLoader,
                MaterialBootstrapFailure.Source.CONFIGURATION_SNAPSHOT);
        return material.thenCombine(configuration, this::joinInputs);
    }

    private MaterialBootstrapResult<R> joinInputs(
            MaterialBootstrapLoadResult<M> materialResult,
            MaterialBootstrapLoadResult<C> configurationResult) {
        if (materialResult instanceof MaterialBootstrapLoadResult.Failed<M> materialFailure) {
            if (configurationResult instanceof MaterialBootstrapLoadResult.Failed<C> configurationFailure) {
                return new MaterialBootstrapResult.Failed<>(List.of(
                        materialFailure.failure(),
                        configurationFailure.failure()));
            }
            return new MaterialBootstrapResult.Failed<>(List.of(materialFailure.failure()));
        }
        if (configurationResult instanceof MaterialBootstrapLoadResult.Failed<C> configurationFailure) {
            return new MaterialBootstrapResult.Failed<>(List.of(configurationFailure.failure()));
        }

        MaterialBootstrapInput<M> material =
                ((MaterialBootstrapLoadResult.Loaded<M>) materialResult).input();
        MaterialBootstrapInput<C> configuration =
                ((MaterialBootstrapLoadResult.Loaded<C>) configurationResult).input();
        return prepareAndPublish(material, configuration);
    }

    private MaterialBootstrapResult<R> prepareAndPublish(
            MaterialBootstrapInput<M> material,
            MaterialBootstrapInput<C> configuration) {
        MaterialBootstrapPreparation<R> preparation;
        try {
            preparation = candidateFactory.prepare(material, configuration);
        } catch (RuntimeException failure) {
            return failed(internalFailure(MaterialBootstrapFailure.Stage.PREPARE, failure));
        }
        if (preparation == null) {
            return failed(internalFailure(MaterialBootstrapFailure.Stage.PREPARE, null));
        }
        if (preparation instanceof MaterialBootstrapPreparation.Failed<R> failed) {
            return failed(failed.failure());
        }

        R runtimeState = ((MaterialBootstrapPreparation.Ready<R>) preparation).runtimeState();
        MaterialBootstrapCandidate<R> candidate = new MaterialBootstrapCandidate<>(
                material.revision(),
                configuration.revision(),
                runtimeState);
        return publish(candidate);
    }

    private MaterialBootstrapResult<R> publish(MaterialBootstrapCandidate<R> candidate) {
        MaterialBootstrapPublication publication;
        try {
            publication = publisher.publish(candidate);
        } catch (RuntimeException failure) {
            return failed(internalFailure(MaterialBootstrapFailure.Stage.PUBLISH, failure));
        }
        if (publication == null) {
            return failed(internalFailure(MaterialBootstrapFailure.Stage.PUBLISH, null));
        }
        if (publication instanceof MaterialBootstrapPublication.Failed failed) {
            return failed(failed.failure());
        }
        return new MaterialBootstrapResult.Activated<>(candidate);
    }

    private <T> CompletionStage<MaterialBootstrapLoadResult<T>> startLoad(
            MaterialBootstrapLoader<T> loader,
            MaterialBootstrapFailure.Source source) {
        CompletionStage<MaterialBootstrapLoadResult<T>> stage;
        try {
            stage = loader.load();
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(loadFailure(source, failure));
        }
        if (stage == null) {
            return CompletableFuture.completedFuture(loadFailure(source, null));
        }
        return stage.handle((result, failure) -> {
            if (failure != null) {
                return loadFailure(source, unwrap(failure));
            }
            if (result == null) {
                return loadFailure(source, null);
            }
            return result;
        });
    }

    private static <T> MaterialBootstrapLoadResult<T> loadFailure(
            MaterialBootstrapFailure.Source source,
            Throwable cause) {
        MaterialBootstrapFailure.Code code = cause == null
                ? MaterialBootstrapFailure.Code.INTERNAL
                : MaterialBootstrapFailure.Code.UNAVAILABLE;
        String message = cause == null
                ? "Bootstrap loader returned no result"
                : "Bootstrap input is unavailable";
        return new MaterialBootstrapLoadResult.Failed<>(new MaterialBootstrapFailure(
                source,
                MaterialBootstrapFailure.Stage.LOAD,
                code,
                message,
                cause));
    }

    private static MaterialBootstrapFailure internalFailure(
            MaterialBootstrapFailure.Stage stage,
            Throwable cause) {
        String message = cause == null
                ? "Bootstrap stage returned no result"
                : "Bootstrap stage failed unexpectedly";
        return new MaterialBootstrapFailure(
                stage == MaterialBootstrapFailure.Stage.PUBLISH
                        ? MaterialBootstrapFailure.Source.RUNTIME
                        : MaterialBootstrapFailure.Source.INPUT_PAIR,
                stage,
                MaterialBootstrapFailure.Code.INTERNAL,
                message,
                cause);
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof CompletionException && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }

    private static <T> MaterialBootstrapResult<T> failed(MaterialBootstrapFailure failure) {
        return new MaterialBootstrapResult.Failed<>(List.of(failure));
    }
}
