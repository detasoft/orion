package pro.deta.orion.git.s3;

import io.minio.*;
import io.minio.errors.*;
import io.minio.messages.Bucket;
import io.minio.messages.Item;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Slf4j
public class M4Client extends AbstractClient {
    private final MinioClient m4Client;

    public M4Client(String bucketName, String path, MinioClient m4Client) {
        super(bucketName, path);
        this.m4Client = m4Client;
    }

    @Override
    public void createBucket() {
        wrapInExceptionProcessing(() -> {
            m4Client.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
            return null;
        });
    }

    private <R> R wrapInExceptionProcessing(Callable<R> callable) {
        try {
            return callable.call();
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            throw new IllegalStateException(e);
        }
    }

    @Override
    public List<String> listBuckets() {
        return wrapInExceptionProcessing(() -> m4Client.listBuckets().stream().map(Bucket::name).toList());
    }

    @Override
    public void createKey(String key, String content) {
        wrapInExceptionProcessing(() -> {
            ByteArrayInputStream arrayInputStream = new ByteArrayInputStream(content.getBytes());
            ObjectWriteResponse resp = m4Client.putObject(PutObjectArgs.builder().bucket(bucketName).object(path + "/" + key).stream(arrayInputStream, arrayInputStream.available(), -1).build());
            log.trace("PutObjectResponse: {}", resp);
            return null;
        });
    }

    @Override
    public void removeBucket() {
        wrapInExceptionProcessing(() -> {
            m4Client.removeBucket(RemoveBucketArgs.builder().bucket(bucketName).build());
            return null;
        });
    }

    @Override
    public Map<String, String> listKeys(String prefix) {
        return wrapInExceptionProcessing(() -> {
            Iterable<Result<Item>> it1 = m4Client.listObjects(ListObjectsArgs.builder().bucket(bucketName).recursive(true).prefix(path + prefix).build());
            return StreamSupport.stream(it1.spliterator(), false).collect(Collectors.toMap(it -> {
                try {
                    return it.get().objectName();
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                }
                return null;
            }, it -> {
                try {
                    return it.get().userMetadata().get(REFERENCE_OBJECT_ID);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        });
    }
}
