package pro.deta.orion.util;

import org.junit.jupiter.api.Test;
import pro.deta.orion.event.OrionEventManager;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VcResourceTest {
    @Test
    void readReturnsTheInferredResultAndClosesTheOperation() {
        TestResource resource = new TestResource();
        VcRevision.Stored revision = new VcRevision.Stored("stored");

        Boolean matches = resource.read(revision, operation -> operation.revision().equals(revision));

        assertThat(matches).isTrue();
        assertThat(resource.closes).isEqualTo(1);
    }

    @Test
    void readPreservesCheckedFailuresAndClosesTheOperation() {
        TestResource resource = new TestResource();
        IOException failure = new IOException("read failed");

        assertThatThrownBy(() -> resource.read(new VcRevision.Stored("stored"), operation -> {
            throw failure;
        })).isSameAs(failure);

        assertThat(resource.closes).isEqualTo(1);
    }

    @Test
    void cleanupFailureDoesNotReplaceTheReadFailure() {
        TestResource resource = new TestResource();
        IOException failure = new IOException("read failed");
        resource.closeFailure = new IllegalStateException("close failed");

        assertThatThrownBy(() -> resource.read(new VcRevision.Stored("stored"), operation -> {
            throw failure;
        })).isSameAs(failure);

        assertThat(failure.getSuppressed()).containsExactly(resource.closeFailure);
        assertThat(resource.closes).isEqualTo(1);
    }

    private static final class TestResource extends VcResource<TestResource.Access> {
        private int closes;
        private RuntimeException closeFailure;

        private TestResource() {
            super(new OrionEventManager());
        }

        @Override
        protected Access createOperation(VcRevision.ReadTarget revision) {
            return new Access((VcRevision.Stored) revision);
        }

        private final class Access extends VcOperation {
            private Access(VcRevision.Stored revision) {
                super(revision);
            }

            @Override
            protected VcRevision.Stored commitChanges(VcChangeDescription description) {
                throw new AssertionError("Read must not commit");
            }

            @Override
            public void close() {
                closes++;
                if (closeFailure != null) throw closeFailure;
            }
        }
    }
}
