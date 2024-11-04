package pro.deta.orion.util;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public class Pair<F, S> {
    private final F first;
    private final S second;

    public static <K,V> Pair<K, V> of(K k, V v) {
        return new Pair<>(k,v);
    }

    @Override
    public String toString() {
        return "Pair{" +
                "first=" + first +
                ", second=" + second +
                '}';
    }
}
