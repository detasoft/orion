package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import pro.deta.orion.config.schema.OrionConfiguration;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Singleton
public final class OrionConfigurationJsonSchema {
    @Inject
    public OrionConfigurationJsonSchema() {
    }

    public Map<String, Object> document() {
        Map<String, Object> schema = objectSchema(OrionConfiguration.class, new OrionConfiguration());
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("$id", "https://deta.pro/orion/schema/orion-configuration.schema.json");
        schema.put("title", "Orion server configuration");
        schema.put("description", "Schema for Orion YAML and TOML configuration files.");
        return schema;
    }

    private static Map<String, Object> objectSchema(Class<?> type, Object value) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);

        Map<String, Object> properties = new LinkedHashMap<>();
        for (Field field : configurationFields(type)) {
            Object fieldValue = fieldValue(field, value);
            properties.put(field.getName(), fieldSchema(field.getType(), fieldValue));
        }
        schema.put("properties", properties);
        return schema;
    }

    private static Map<String, Object> fieldSchema(Class<?> type, Object value) {
        if (type == String.class) {
            return simpleSchema("string", value);
        }
        if (type == boolean.class || type == Boolean.class) {
            return simpleSchema("boolean", value);
        }
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class) {
            return simpleSchema("integer", value);
        }
        if (type.isEnum()) {
            Map<String, Object> schema = simpleSchema("string", value);
            schema.put("enum", enumOptions(type));
            return schema;
        }
        if (Collection.class.isAssignableFrom(type)) {
            Map<String, Object> schema = simpleSchema("array", value);
            schema.put("items", Map.of("type", "string"));
            return schema;
        }
        if (Map.class.isAssignableFrom(type)) {
            Map<String, Object> schema = simpleSchema("object", null);
            schema.put("additionalProperties", Map.of("type", "string"));
            return schema;
        }
        return objectSchema(type, value != null ? value : newInstance(type));
    }

    private static Map<String, Object> simpleSchema(String type, Object value) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", type);
        Object defaultValue = defaultValue(value);
        if (defaultValue != null) {
            schema.put("default", defaultValue);
        }
        return schema;
    }

    private static Object defaultValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Collection<?> collection && collection.isEmpty()) {
            return null;
        }
        if (value instanceof Map<?, ?> map && map.isEmpty()) {
            return null;
        }
        return value;
    }

    private static List<Field> configurationFields(Class<?> type) {
        List<Class<?>> hierarchy = new ArrayList<>();
        Class<?> next = type;
        while (next != null && next != Object.class) {
            hierarchy.add(0, next);
            next = next.getSuperclass();
        }

        List<Field> fields = new ArrayList<>();
        for (Class<?> hierarchyType : hierarchy) {
            for (Field field : hierarchyType.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) {
                    continue;
                }
                fields.add(field);
            }
        }
        return fields;
    }

    private static Object fieldValue(Field field, Object source) {
        if (source == null) {
            return null;
        }
        try {
            field.setAccessible(true);
            return field.get(source);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read configuration field " + field.getName(), e);
        }
    }

    private static Object newInstance(Class<?> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static List<String> enumOptions(Class<?> type) {
        List<String> options = new ArrayList<>();
        for (Object enumConstant : type.getEnumConstants()) {
            options.add(String.valueOf(enumConstant));
        }
        return options;
    }
}
