package pro.deta.orion.git.s3;


import com.google.common.collect.ImmutableList;
import io.minio.*;
import io.minio.errors.*;
import io.minio.messages.Bucket;
import io.minio.messages.Item;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.Ref;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@RequiredArgsConstructor
@Getter
public abstract class AbstractClient {
    protected static String REFERENCE_OBJECT_ID = "REFERENCE_OBJECT_ID";

    protected final String bucketName;
    protected final String path;

    public abstract void createBucket();

    public abstract List<String> listBuckets();

    public abstract void createKey(String key, String content);

    public abstract void removeBucket();

    public abstract Map<String, String> listKeys(String prefix);
}
