package pro.deta.orion.git.s3;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.ObjectDatabase;
import org.eclipse.jgit.transport.PackParser;
import org.eclipse.jgit.transport.PackedObjectInfo;

import java.io.IOException;
import java.io.InputStream;

@Slf4j
public class S3PackParser extends PackParser {
    private final AbstractClient client;
    private String currentPackKey;
    private long currentObjectOffset;
    private static final String PACK_PREFIX = "pack-";
    private static final String OBJECTS_PREFIX = "objects/";

    public S3PackParser(S3ObjectDatabase s3ObjectDatabase, InputStream in, AbstractClient client) {
        super(s3ObjectDatabase, in);
        this.client = client;
    }

    @Override
    protected void onStoreStream(byte[] raw, int pos, int len) throws IOException {
        // Store the raw pack stream data
        if (currentPackKey == null) {
            currentPackKey = String.format("%s/%s%d", client.getPath(), PACK_PREFIX, System.currentTimeMillis());
        }
        // Store raw pack data in chunks
        String chunkKey = String.format("%s/chunk-%d", currentPackKey, currentObjectOffset);
        storeBytes(chunkKey, raw, pos, len);
        currentObjectOffset += len;
    }

    @Override
    protected void onObjectHeader(Source src, byte[] raw, int pos, int len) throws IOException {
        // Process object header information
        String headerKey = String.format("%s/%s%d/header", client.getPath(), OBJECTS_PREFIX, currentObjectOffset);
        storeBytes(headerKey, raw, pos, len);
    }

    @Override
    protected void onObjectData(Source src, byte[] raw, int pos, int len) throws IOException {
        // Store raw object data
        String dataKey = String.format("%s/%s%d/data", client.getPath(), OBJECTS_PREFIX, currentObjectOffset);
        storeBytes(dataKey, raw, pos, len);
    }

    @Override
    protected void onInflatedObjectData(PackedObjectInfo obj, int typeCode, byte[] data) throws IOException {
        // Store the fully inflated object with its SHA-1 ID
        String objectKey = String.format("%s/%s%s", client.getPath(), OBJECTS_PREFIX, obj.name());
        storeBytes(objectKey, data, 0, data.length);
    }

    private void storeBytes(String key, byte[] data, int offset, int length) throws IOException {
        byte[] chunk = new byte[length];
        System.arraycopy(data, offset, chunk, 0, length);
        // Store binary data using Base64 encoding to preserve binary content
        String base64Content = java.util.Base64.getEncoder().encodeToString(chunk);
        client.createKey(key, base64Content);
    }

    @Override
    protected void onPackHeader(long objCnt) throws IOException {
        // Store pack metadata
        String metadataKey = String.format("%s/metadata", currentPackKey);
        String metadata = String.format("object_count=%d", objCnt);
        client.createKey(metadataKey, metadata);
    }

    @Override
    protected void onPackFooter(byte[] hash) throws IOException {
        // Store pack hash
        String hashKey = String.format("%s/hash", currentPackKey);
        storeBytes(hashKey, hash, 0, hash.length);
    }

    @Override
    protected boolean onAppendBase(int typeCode, byte[] data, PackedObjectInfo info) throws IOException {
        // Store base object for delta
        String baseKey = String.format("%s/%s%s/base", client.getPath(), OBJECTS_PREFIX, info.name());
        storeBytes(baseKey, data, 0, data.length);
        return true;
    }

    @Override
    protected void onEndThinPack() throws IOException {
        // Mark pack as complete
        String statusKey = String.format("%s/status", currentPackKey);
        client.createKey(statusKey, "complete");
    }

    @Override
    protected ObjectTypeAndSize seekDatabase(PackedObjectInfo obj, ObjectTypeAndSize info) throws IOException {
        // We don't support seeking in the database
        return null;
    }

    @Override
    protected ObjectTypeAndSize seekDatabase(UnresolvedDelta delta, ObjectTypeAndSize info) throws IOException {
        // We don't support seeking in the database
        return null;
    }

    @Override
    protected int readDatabase(byte[] dst, int pos, int cnt) throws IOException {
        // We don't support reading from the database
        return 0;
    }

    @Override
    protected boolean checkCRC(int oldCRC) {
        // We don't verify CRC
        return true;
    }

    @Override
    protected void onBeginWholeObject(long streamPosition, int type, long inflatedSize) throws IOException {
        // Store object metadata
        String metadataKey = String.format("%s/%s%d/metadata", client.getPath(), OBJECTS_PREFIX, currentObjectOffset);
        String metadata = String.format("type=%d,size=%d,position=%d", type, inflatedSize, streamPosition);
        client.createKey(metadataKey, metadata);
    }

    @Override
    protected void onEndWholeObject(PackedObjectInfo info) throws IOException {
        // Update object with its final name
        String objectKey = String.format("%s/%s%s", client.getPath(), OBJECTS_PREFIX, info.name());
        String metadataKey = String.format("%s/metadata", objectKey);
        client.createKey(metadataKey, "complete");
    }

    @Override
    protected void onBeginOfsDelta(long deltaStreamPosition, long baseStreamPosition, long inflatedSize) throws IOException {
        // Store offset delta metadata
        String deltaKey = String.format("%s/%s%d/delta", client.getPath(), OBJECTS_PREFIX, currentObjectOffset);
        String metadata = String.format("type=ofs,base_position=%d,size=%d,position=%d",
                baseStreamPosition, inflatedSize, deltaStreamPosition);
        client.createKey(deltaKey, metadata);
    }

    @Override
    protected void onBeginRefDelta(long deltaStreamPosition, AnyObjectId baseId, long inflatedSize) throws IOException {
        // Store ref delta metadata
        String deltaKey = String.format("%s/%s%d/delta", client.getPath(), OBJECTS_PREFIX, currentObjectOffset);
        String metadata = String.format("type=ref,base_id=%s,size=%d,position=%d",
                baseId.name(), inflatedSize, deltaStreamPosition);
        client.createKey(deltaKey, metadata);
    }
}
