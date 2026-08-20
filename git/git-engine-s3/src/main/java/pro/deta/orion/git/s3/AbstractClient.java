package pro.deta.orion.git.s3;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;

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
