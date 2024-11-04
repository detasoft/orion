package pro.deta.orion.util;

import lombok.Getter;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.StringJoiner;

@Getter
public class OpenSSHKey {
    public static final Base64.Decoder BASE64_DECODER = Base64.getDecoder();
    private static final byte[] MAGIC = "openssh-key-v1".getBytes(StandardCharsets.UTF_8);

    private ByteArrayMap.Entry ciphername;
    private ByteArrayMap.Entry kdfname;
    private ByteArrayMap.Entry kdf;
    private ByteArrayMap.Entry numKeys;
    private ByteArrayMap.Entry publicKey;
    private ByteArrayMap.Entry privateKey;

    public static OpenSSHKey decode(String base64Data) {
        byte[] data = BASE64_DECODER.decode(base64Data);
        byte[] data1 = stripMagicPrefixIfAny(data);
        OpenSSHKey key = new OpenSSHKey();
        ByteArrayMap bam = new ByteArrayMap(data1, ByteArrayMap.RunningLengthSize.LENGTH_SIZE_4);
        key.ciphername = bam.readRLEEntry();
        key.kdfname = bam.readRLEEntry();
        key.kdf = bam.readRLEEntry();
        key.numKeys = bam.read4ByteAsInt();
        key.publicKey = bam.readRLEEntry();
        key.privateKey = bam.readRLEEntry();
        bam.endParse();
        return key;
    }

    private static byte[] stripMagicPrefixIfAny(byte[] data) {
        if (Arrays.equals(data, 0, MAGIC.length, MAGIC, 0, MAGIC.length)) {
            return Arrays.copyOfRange(data, MAGIC.length+1, data.length);
        }
        return data;
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", OpenSSHKey.class.getSimpleName() + "[", "]")
                .add("ciphername=" + getStringCipherName())
                .add("kdfname=" + getStringKdfName())
                .add("kdf=" + getStringKdfOptions())
                .add("numKeys=" + getIntegerNumKeys())
                .add(getStringKey("publicKey=", publicKey))
                .add(getStringKey("privateKey=", privateKey))
                .toString();
    }

    private String getStringKey(String prefix, ByteArrayMap.Entry key) {
        if (key == null)
            return "";
        return prefix + key.getBeginIdx() + ":" + key.getLength();
    }

    private int getIntegerNumKeys() {
        return numKeys != null ? numKeys.getInteger() :  -1;
    }

    private String getStringKdfOptions() {
        if (kdf == null)
            return "<none>";
        return kdf.getHexString(true);
    }

    private String getStringFromEntry(ByteArrayMap.Entry entry) {
        if (entry == null)
            return "<none>";
        return entry.getString();
    }

    private String getStringKdfName() {
        return getStringFromEntry(kdfname);
    }

    private String getStringCipherName() {
        return getStringFromEntry(ciphername);
    }
}
