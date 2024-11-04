package pro.deta.orion.comm.common;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class TypedMap {
    private final Map<Key<?>, Object> map = new HashMap<>();

    public static class Key<T> {
        private final Class<T> type;
        private final String name;

        public Key(Class<T> type, String name) {
            this.type = type;
            this.name = name;
        }

        Class<T> getType() {
            return type;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key<?> other)) return false;
            return Objects.equals(type, other.type) && Objects.equals(name, other.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, name);
        }
    }

    public <T> void put(Key<T> key, T value) {
        map.put(key, key.getType().cast(value));
    }

    public <T> T get(Key<T> key) {
        return key.getType().cast(map.get(key));
    }
}
