package pro.deta.orion.keymaterial.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;

class MaterialBootstrapCoordinatorTest {
    private static final String MATERIAL_REVISION = "material-r1";
    private static final String CONFIGURATION_REVISION = "configuration-c1";

    @Test
    void materialCompletionWaitsForConfigurationBeforePreparingAndPublishing() {
        CompletableFuture<MaterialBootstrapLoadResult<String>> material = new CompletableFuture<>();
        CompletableFuture<MaterialBootstrapLoadResult<String>> configuration = new CompletableFuture<>();
        RecordingCandidateFactory factory = new RecordingCandidateFactory();
        RecordingPublisher publisher = new RecordingPublisher();
        CompletableFuture<MaterialBootstrapResult<String>> attempt = coordinator(
                () -> material,
                () -> configuration,
                factory,
                publisher).bootstrap().toCompletableFuture();

        material.complete(loaded(MATERIAL_REVISION, "material"));

        assertThat(attempt).isNotDone();
        assertThat(factory.calls).isZero();
        assertThat(publisher.calls).isZero();

        configuration.complete(loaded(CONFIGURATION_REVISION, "configuration"));

        MaterialBootstrapCandidate<String> candidate = activated(attempt.join()).candidate();
        assertThat(candidate.materialRevision()).isEqualTo(MATERIAL_REVISION);
        assertThat(candidate.configurationRevision()).isEqualTo(CONFIGURATION_REVISION);
        assertThat(candidate.runtimeState()).isEqualTo("material+configuration");
        assertThat(factory.calls).isOne();
        assertThat(publisher.active).isEqualTo(candidate);
    }

    @Test
    void configurationCompletionWaitsForMaterialBeforePreparingAndPublishing() {
        CompletableFuture<MaterialBootstrapLoadResult<String>> material = new CompletableFuture<>();
        CompletableFuture<MaterialBootstrapLoadResult<String>> configuration = new CompletableFuture<>();
        RecordingCandidateFactory factory = new RecordingCandidateFactory();
        RecordingPublisher publisher = new RecordingPublisher();
        CompletableFuture<MaterialBootstrapResult<String>> attempt = coordinator(
                () -> material,
                () -> configuration,
                factory,
                publisher).bootstrap().toCompletableFuture();

        configuration.complete(loaded(CONFIGURATION_REVISION, "configuration"));

        assertThat(attempt).isNotDone();
        assertThat(factory.calls).isZero();
        assertThat(publisher.calls).isZero();

        material.complete(loaded(MATERIAL_REVISION, "material"));

        assertThat(attempt.join()).isInstanceOf(MaterialBootstrapResult.Activated.class);
        assertThat(factory.calls).isOne();
        assertThat(publisher.calls).isOne();
    }

    @Test
    void reportsBothLoadFailuresInMaterialThenConfigurationOrder() {
        CompletableFuture<MaterialBootstrapLoadResult<String>> material = new CompletableFuture<>();
        CompletableFuture<MaterialBootstrapLoadResult<String>> configuration = new CompletableFuture<>();
        RecordingCandidateFactory factory = new RecordingCandidateFactory();
        RecordingPublisher publisher = new RecordingPublisher();
        CompletableFuture<MaterialBootstrapResult<String>> attempt = coordinator(
                () -> material,
                () -> configuration,
                factory,
                publisher).bootstrap().toCompletableFuture();

        configuration.complete(failed(
                MaterialBootstrapFailure.Source.CONFIGURATION_SNAPSHOT,
                MaterialBootstrapFailure.Code.CORRUPT));
        material.complete(failed(
                MaterialBootstrapFailure.Source.MATERIAL_STORE,
                MaterialBootstrapFailure.Code.MISSING));

        List<MaterialBootstrapFailure> failures = failures(attempt.join());
        assertThat(failures)
                .extracting(MaterialBootstrapFailure::source)
                .containsExactly(
                        MaterialBootstrapFailure.Source.MATERIAL_STORE,
                        MaterialBootstrapFailure.Source.CONFIGURATION_SNAPSHOT);
        assertThat(factory.calls).isZero();
        assertThat(publisher.calls).isZero();
    }

    @ParameterizedTest
    @EnumSource(
            value = MaterialBootstrapFailure.Code.class,
            names = {"MISSING", "CORRUPT", "INCOMPATIBLE"})
    void typedInputFailurePreventsPreparationAndPublication(MaterialBootstrapFailure.Code code) {
        RecordingCandidateFactory factory = new RecordingCandidateFactory();
        RecordingPublisher publisher = new RecordingPublisher();
        MaterialBootstrapResult<String> result = coordinator(
                completed(failed(MaterialBootstrapFailure.Source.MATERIAL_STORE, code)),
                completed(loaded(CONFIGURATION_REVISION, "configuration")),
                factory,
                publisher).bootstrap().toCompletableFuture().join();

        assertThat(failures(result)).singleElement().satisfies(failure -> {
            assertThat(failure.source()).isEqualTo(MaterialBootstrapFailure.Source.MATERIAL_STORE);
            assertThat(failure.code()).isEqualTo(code);
        });
        assertThat(factory.calls).isZero();
        assertThat(publisher.calls).isZero();
    }

    @Test
    void exceptionalLoadIsReportedAsUnavailableWithoutPublishing() {
        RecordingCandidateFactory factory = new RecordingCandidateFactory();
        RecordingPublisher publisher = new RecordingPublisher();
        CompletableFuture<MaterialBootstrapLoadResult<String>> unavailable = new CompletableFuture<>();
        unavailable.completeExceptionally(new IllegalStateException("secret diagnostic"));
        MaterialBootstrapResult<String> result = coordinator(
                () -> unavailable,
                completed(loaded(CONFIGURATION_REVISION, "configuration")),
                factory,
                publisher).bootstrap().toCompletableFuture().join();

        MaterialBootstrapFailure failure = failures(result).getFirst();
        assertThat(failure.code()).isEqualTo(MaterialBootstrapFailure.Code.UNAVAILABLE);
        assertThat(failure.message()).doesNotContain("secret diagnostic");
        assertThat(factory.calls).isZero();
        assertThat(publisher.calls).isZero();
    }

    @Test
    void mismatchedPairLeavesPreviouslyActiveCandidateUnchanged() {
        MaterialBootstrapCandidate<String> previous =
                new MaterialBootstrapCandidate<>("material-r0", "configuration-c0", "previous");
        RecordingPublisher publisher = new RecordingPublisher(previous);
        MaterialBootstrapFailure mismatch = new MaterialBootstrapFailure(
                MaterialBootstrapFailure.Source.INPUT_PAIR,
                MaterialBootstrapFailure.Stage.PREPARE,
                MaterialBootstrapFailure.Code.MISMATCHED,
                "Configuration references unavailable material");
        MaterialBootstrapResult<String> result = coordinator(
                completed(loaded(MATERIAL_REVISION, "material")),
                completed(loaded(CONFIGURATION_REVISION, "configuration")),
                (material, configuration) -> new MaterialBootstrapPreparation.Failed<>(mismatch),
                publisher).bootstrap().toCompletableFuture().join();

        assertThat(failures(result)).containsExactly(mismatch);
        assertThat(publisher.active).isSameAs(previous);
        assertThat(publisher.calls).isZero();
    }

    @Test
    void publicationFailureLeavesPreviouslyActiveCandidateUnchanged() {
        MaterialBootstrapCandidate<String> previous =
                new MaterialBootstrapCandidate<>("material-r0", "configuration-c0", "previous");
        MaterialBootstrapFailure publicationFailure = new MaterialBootstrapFailure(
                MaterialBootstrapFailure.Source.RUNTIME,
                MaterialBootstrapFailure.Stage.PUBLISH,
                MaterialBootstrapFailure.Code.ACTIVATION_FAILED,
                "Runtime snapshot was not replaced");
        RecordingPublisher publisher = new RecordingPublisher(previous, publicationFailure);
        MaterialBootstrapResult<String> result = coordinator(
                completed(loaded(MATERIAL_REVISION, "material")),
                completed(loaded(CONFIGURATION_REVISION, "configuration")),
                new RecordingCandidateFactory(),
                publisher).bootstrap().toCompletableFuture().join();

        assertThat(failures(result)).containsExactly(publicationFailure);
        assertThat(publisher.active).isSameAs(previous);
        assertThat(publisher.calls).isOne();
    }

    @Test
    void freshCoordinatorReconstructsTheSameDurablePairAfterRestart() {
        RecordingPublisher publisher = new RecordingPublisher();
        MaterialBootstrapResult<String> first = completedCoordinator(publisher)
                .bootstrap().toCompletableFuture().join();
        MaterialBootstrapCandidate<String> firstCandidate = activated(first).candidate();

        MaterialBootstrapResult<String> restarted = completedCoordinator(publisher)
                .bootstrap().toCompletableFuture().join();
        MaterialBootstrapCandidate<String> restartedCandidate = activated(restarted).candidate();

        assertThat(restartedCandidate).isEqualTo(firstCandidate);
        assertThat(publisher.active).isEqualTo(firstCandidate);
        assertThat(publisher.calls).isEqualTo(2);
    }

    private static MaterialBootstrapCoordinator<String, String, String> completedCoordinator(
            RecordingPublisher publisher) {
        return coordinator(
                completed(loaded(MATERIAL_REVISION, "material")),
                completed(loaded(CONFIGURATION_REVISION, "configuration")),
                new RecordingCandidateFactory(),
                publisher);
    }

    private static MaterialBootstrapCoordinator<String, String, String> coordinator(
            MaterialBootstrapLoader<String> material,
            MaterialBootstrapLoader<String> configuration,
            MaterialBootstrapCandidateFactory<String, String, String> factory,
            MaterialBootstrapPublisher<String> publisher) {
        return new MaterialBootstrapCoordinator<>(material, configuration, factory, publisher);
    }

    private static MaterialBootstrapLoader<String> completed(MaterialBootstrapLoadResult<String> result) {
        return () -> CompletableFuture.completedFuture(result);
    }

    private static MaterialBootstrapLoadResult<String> loaded(String revision, String value) {
        return new MaterialBootstrapLoadResult.Loaded<>(new MaterialBootstrapInput<>(revision, value));
    }

    private static MaterialBootstrapLoadResult<String> failed(
            MaterialBootstrapFailure.Source source,
            MaterialBootstrapFailure.Code code) {
        return new MaterialBootstrapLoadResult.Failed<>(new MaterialBootstrapFailure(
                source,
                MaterialBootstrapFailure.Stage.LOAD,
                code,
                "Bootstrap input failed"));
    }

    @SuppressWarnings("unchecked")
    private static MaterialBootstrapResult.Activated<String> activated(MaterialBootstrapResult<String> result) {
        assertThat(result).isInstanceOf(MaterialBootstrapResult.Activated.class);
        return (MaterialBootstrapResult.Activated<String>) result;
    }

    @SuppressWarnings("unchecked")
    private static List<MaterialBootstrapFailure> failures(MaterialBootstrapResult<String> result) {
        assertThat(result).isInstanceOf(MaterialBootstrapResult.Failed.class);
        return ((MaterialBootstrapResult.Failed<String>) result).failures();
    }

    private static final class RecordingCandidateFactory
            implements MaterialBootstrapCandidateFactory<String, String, String> {
        private int calls;

        @Override
        public MaterialBootstrapPreparation<String> prepare(
                MaterialBootstrapInput<String> material,
                MaterialBootstrapInput<String> configuration) {
            calls++;
            return new MaterialBootstrapPreparation.Ready<>(material.value() + "+" + configuration.value());
        }
    }

    private static final class RecordingPublisher implements MaterialBootstrapPublisher<String> {
        private final MaterialBootstrapFailure failure;
        private MaterialBootstrapCandidate<String> active;
        private int calls;

        private RecordingPublisher() {
            this(null, null);
        }

        private RecordingPublisher(MaterialBootstrapCandidate<String> active) {
            this(active, null);
        }

        private RecordingPublisher(
                MaterialBootstrapCandidate<String> active,
                MaterialBootstrapFailure failure) {
            this.active = active;
            this.failure = failure;
        }

        @Override
        public MaterialBootstrapPublication publish(MaterialBootstrapCandidate<String> candidate) {
            calls++;
            if (failure != null) {
                return new MaterialBootstrapPublication.Failed(failure);
            }
            active = candidate;
            return new MaterialBootstrapPublication.Published();
        }
    }
}
