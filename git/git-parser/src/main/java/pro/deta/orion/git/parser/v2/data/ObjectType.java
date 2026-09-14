package pro.deta.orion.git.parser.v2.data;

/**
 * Identifies the logical type of a restored Git object, independently of its packed representation.
 * Delta encodings are storage details and do not introduce additional object types.
 */
public enum ObjectType {
    COMMIT,
    TREE,
    BLOB,
    TAG
}
