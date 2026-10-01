package pro.deta.orion.git.s3;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.parser.v2.index.GitIndexAccess;
import pro.deta.orion.git.parser.v2.index.RefSelection;
import pro.deta.orion.git.parser.v2.storage.GitStorageAccess;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class S3GitAccessTest {
    @Test
    void closingOwnersPreventsNewAccessesButExistingAccessesCanFinish() throws Exception {
        try (S3TransportTest.Server server = new S3TransportTest.Server(new CountDownLatch(0), new CountDownLatch(0));
             S3Transport transport = new S3Transport();
             NativeGitRepository repository = transport.repositories("s3://bucket/repos", server.endpoint(),
                     "us-east-1", true, Optional.of(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
                             .create(software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("id", "key"))))
                     .create("repo").valueOrFailure("create")) {
            GitIndexAccess index = repository.index().createAccess();
            GitStorageAccess storage = repository.storage().createAccess();
            repository.close();
            assertThat(index.snapshotRefs(new RefSelection.All()).refs()).isEmpty();
            index.apply();
            index.discard();
            storage.discard();
            storage.discard();
            assertThatThrownBy(repository.index()::createAccess).isInstanceOf(IOException.class);
            assertThatThrownBy(repository.storage()::createAccess).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> index.snapshotRefs(new RefSelection.All())).isInstanceOf(IOException.class);
        }
    }
}
