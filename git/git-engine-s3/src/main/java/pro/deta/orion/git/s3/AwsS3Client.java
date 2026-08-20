package pro.deta.orion.git.s3;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class AwsS3Client extends AbstractClient {
    private final S3Client s3Client;

    public AwsS3Client(String bucketName, String path, S3Client s3Client) {
        super(bucketName, path);
        this.s3Client = s3Client;
    }

    @Override
    public void createBucket() {
        CreateBucketResponse resp = s3Client.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());
        log.trace("CreateBucketResponse: {}", resp);
    }

    @Override
    public List<String> listBuckets() {
        return s3Client.listBuckets().buckets().stream().map(software.amazon.awssdk.services.s3.model.Bucket::name).toList();
    }

    @Override
    public void createKey(String key, String content) {
        PutObjectResponse root = s3Client.putObject(PutObjectRequest.builder().bucket(bucketName).key(path + "/" + key).build(), RequestBody.fromString(content));
        log.trace("PutObjectResponse: {}", root);
    }

    @Override
    public void removeBucket() {
        try {
            DeleteBucketResponse root = s3Client.deleteBucket(DeleteBucketRequest.builder().bucket(bucketName).build());
            log.trace("DeleteBucketResponse: {}", root);
        } catch (S3Exception e) {
            log.warn("Bucket {} not exist.", bucketName);
        }
    }

    @Override
    public Map<String, String> listKeys(String prefix) {
        try {
            ListObjectsV2Response root = s3Client.listObjectsV2(ListObjectsV2Request.builder().optionalObjectAttributesWithStrings(REFERENCE_OBJECT_ID).bucket(bucketName).prefix(path).build());
            log.trace("ListObjectsV2Response: {}", root);
            return root.contents().stream().collect(Collectors.toMap(S3Object::key, it->it.getValueForField(REFERENCE_OBJECT_ID, String.class).get()));
        } catch (NoSuchBucketException e) {
            log.warn("Bucket {} not exist.", bucketName);
        }
        return Map.of();
    }

}

