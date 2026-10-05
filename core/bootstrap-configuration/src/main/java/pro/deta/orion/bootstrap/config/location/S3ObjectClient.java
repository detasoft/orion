package pro.deta.orion.bootstrap.config.location;

import java.util.Optional;

interface S3ObjectClient {
    Optional<byte[]> readObject(S3ConfigurationObject object);
}
