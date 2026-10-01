package pro.deta.orion.git.parser.v2.index;

import pro.deta.orion.git.parser.v2.id.RefId;

import java.util.Objects;

/** Selects ref names; every snapshot also carries HEAD, and Head selects it without any refs. */
public sealed interface RefSelection {
    boolean matches(RefId ref);

    record All() implements RefSelection {
        @Override
        public boolean matches(RefId ref) {
            return true;
        }
    }

    record Head() implements RefSelection {
        @Override
        public boolean matches(RefId ref) {
            return false;
        }
    }

    record One(RefId ref) implements RefSelection {
        public One {
            Objects.requireNonNull(ref, "ref").requireFullName();
        }

        @Override
        public boolean matches(RefId candidate) {
            return ref.equals(candidate);
        }
    }

    /** Matches the entire ref name. Only '*' is special; it matches zero or more characters, including '/'. */
    record Pattern(String value) implements RefSelection {
        public Pattern {
            if (Objects.requireNonNull(value, "value").isEmpty()) {
                throw new IllegalArgumentException("Ref pattern must not be empty; use All");
            }
        }

        @Override
        public boolean matches(RefId ref) {
            String name = ref.value();
            int patternIndex = 0;
            int nameIndex = 0;
            int lastStar = -1;
            int retryNameIndex = 0;
            while (nameIndex < name.length()) {
                if (patternIndex < value.length() && value.charAt(patternIndex) == '*') {
                    lastStar = patternIndex++;
                    retryNameIndex = nameIndex;
                } else if (patternIndex < value.length()
                        && value.charAt(patternIndex) == name.charAt(nameIndex)) {
                    patternIndex++;
                    nameIndex++;
                } else if (lastStar >= 0) {
                    patternIndex = lastStar + 1;
                    nameIndex = ++retryNameIndex;
                } else {
                    return false;
                }
            }
            while (patternIndex < value.length() && value.charAt(patternIndex) == '*') {
                patternIndex++;
            }
            return patternIndex == value.length();
        }
    }

    /** Finds a literal, case-sensitive sequence anywhere in the full ref name. */
    record Substring(String value) implements RefSelection {
        public Substring {
            if (Objects.requireNonNull(value, "value").isEmpty()) {
                throw new IllegalArgumentException("Ref substring must not be empty; use All");
            }
        }

        @Override
        public boolean matches(RefId ref) {
            return ref.value().contains(value);
        }
    }
}
