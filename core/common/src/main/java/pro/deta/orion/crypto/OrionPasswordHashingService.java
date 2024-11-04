package pro.deta.orion.crypto;

import jakarta.inject.Inject;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import pro.deta.orion.util.rle.RLETCoder;

import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.Collections.shuffle;
import static java.util.stream.Collectors.collectingAndThen;


public class OrionPasswordHashingService {
    private static final Collector<?, ?, ?> SHUFFLER = collectingAndThen(
            Collectors.toCollection(ArrayList::new),
            list -> {
                shuffle(list);
                return list;
            }
    );
    private static final int HASH_LENGTH = 32;

    private final SecureRandom random;
    private final Base64.Encoder encoder;
    private final Base64.Decoder decoder;

    @Inject
    public OrionPasswordHashingService() {
        encoder = Base64.getEncoder();
        decoder = Base64.getDecoder();
        try {
            random = SecureRandom.getInstance("NativePRNGNonBlocking");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to create secureRandom.", e);
        }
    }

    public char[] generateRandomString(int length) {
        return generateSecureRandomPassword(length);
    }

    public String calculateHash(char[] password) {
        byte[] salt = generateSalt16Byte();
        byte[] r = internalCalculateHash(salt, password);
        byte[] result = RLETCoder.ENCODER_SHORT.encodeRLET(salt, r);
        return String.format("%s", encoder.encodeToString(result));
    }

    private byte[] internalCalculateHash(byte[] salt, char[] password) {
        OrionGenerator generator = getHashBuilder(salt);
        return generator.generateBytes(password, HASH_LENGTH);
    }

    private byte[] internalCalculateHash(byte[] salt, byte[] password) {
        OrionGenerator generator = getHashBuilder(salt);
        return generator.generateBytes(password, HASH_LENGTH);
    }

    private byte[] generateSalt16Byte() {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return salt;
    }

    public char[] generateSecureRandomPassword(int length) {
        return toCharArray(Stream.concat(getRandomNumbers(4), Stream.concat(getRandomSpecialChars(2), Stream.concat(getRandomAlphabets(length / 2, true), getRandomAlphabets(length / 2, false))))
                .collect(collectingAndThen(Collectors.toList(), (List<Character> collected) -> {
                    shuffle(collected);
                    return collected;
                })));
    }

    public static byte[] toByteArray(List<Character> list) {
        int size = list.size();
        byte[] r = new byte[size];
        for (int i = 0; i < size; i++) {
            r[i] = (byte) list.get(i).charValue();
        }
        return r;
    }

    public static char[] toCharArray(List<Character> list) {
        int size = list.size();
        char[] r = new char[size];
        for (int i = 0; i < size; i++) {
            r[i] = list.get(i);
        }
        return r;
    }

    public Stream<Character> getRandomAlphabets(int count, boolean upperCase) {
        IntStream characters = null;
        if (upperCase) {
            characters = random.ints(count, 65, 90);
        } else {
            characters = random.ints(count, 97, 122);
        }
        return characters.mapToObj(data -> (char) data);
    }

    public Stream<Character> getRandomNumbers(int count) {
        IntStream numbers = random.ints(count, 48, 57);
        return numbers.mapToObj(data -> (char) data);
    }

    public Stream<Character> getRandomSpecialChars(int count) {
        IntStream specialChars = random.ints(count, 33, 45);
        return specialChars.mapToObj(data -> (char) data);
    }

    @SuppressWarnings("unchecked")
    public static <T> Collector<T, ?, List<T>> toShuffledList() {
        return (Collector<T, ?, List<T>>) SHUFFLER;
    }

    private OrionGenerator getHashBuilder(byte[] salt) {
        Argon2Parameters.Builder builder = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(2)
                .withMemoryAsKB(66536)
                .withParallelism(1)
                .withSalt(salt);
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(builder.build());
        return new OrionGenerator(generator);
    }

    public boolean comparePassword(String expected, byte[] provided) {
        ByteBuffer expectedByteBuffer = ByteBuffer.wrap(decoder.decode(expected));
        byte[][] parts = RLETCoder.ENCODER_SHORT.decodeRLET(expectedByteBuffer);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Length can't be not equals to two: " + parts.length);
        }
        byte[] actualContent = internalCalculateHash(parts[0], provided);
        return Arrays.equals(actualContent, parts[1]);
    }
}
