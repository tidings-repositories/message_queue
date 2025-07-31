package com.delivalue.tidings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.transforms.Transformation;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.errors.DataException;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class JsonStringToStruct<R extends ConnectRecord<R>> implements Transformation<R> {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public R apply(R record) {
        Object value = record.value();

        if (!(value instanceof Struct message)) {
            return record;
        }

        Object afterValue = message.get("after");

        // after 필드가 String 타입 JSON인 경우만 변환 수행
        if (!(afterValue instanceof String)) {
            return record;
        }
        
        try {
            JsonNode jsonNode = mapper.readTree((String) afterValue);
            if (jsonNode == null || !jsonNode.isObject()) {
                throw new DataException("Invalid JSON content: not an object");
            }

            ObjectNode node = mapper.createObjectNode();
            jsonNode.fields().forEachRemaining(entry -> {
                String k = entry.getKey();
                JsonNode v = entry.getValue();

                if("_id".equals(k)) {
                    node.set("id", v);
                } else if (k.endsWith("At")) {
                    Long timestamp = v.get("$date").asLong();
                    node.set(k, mapper.valueToTree(timestamp));
                } else {
                    node.set(k, v);
                }
            });

            // JSON을 기반 동적 스키마 생성
            Schema afterSchema = buildSchema(node);
            Struct afterStruct = buildStruct(node, afterSchema);

            Schema newSchema = createUpdatedSchema(record.valueSchema(), "after", afterSchema);
            Struct newStruct = new Struct(newSchema);
            for (Field field : newSchema.fields()) {
                if ("after".equals(field.name())) {
                    newStruct.put(field, afterStruct); // after 필드에 Struct 넣기
                } else {
                    newStruct.put(field, message.get(field));
                }
            }

            return record.newRecord(
                    record.topic(),
                    record.kafkaPartition(),
                    record.keySchema(),
                    record.key(),
                    newSchema,
                    newStruct,
                    record.timestamp()
            );
        } catch (Exception e) {
            throw new DataException("Failed to transform JSON string into Struct", e);
        }
    }

    public Schema createUpdatedSchema(Schema originalSchema, String targetField, Schema newFieldSchema) {
        SchemaBuilder builder = SchemaBuilder.struct().name(originalSchema.name()).optional();

        for (Field field : originalSchema.fields()) {
            if (field.name().equals(targetField)) {
                builder.field(field.name(), newFieldSchema);
            } else {
                builder.field(field.name(), field.schema());
            }
        }

        return builder.build();
    }

    private Schema buildSchema(JsonNode jsonNode) {
        SchemaBuilder builder = SchemaBuilder.struct().optional();

        Iterator<Map.Entry<String, JsonNode>> fields = jsonNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String fieldName = field.getKey();
            JsonNode valueNode = field.getValue();

            Schema fieldSchema = inferSchema(valueNode);
            builder.field(fieldName, fieldSchema);
        }

        return builder.build();
    }

    private Schema inferSchema(JsonNode node) {
        if (node.isTextual()) {
            return Schema.OPTIONAL_STRING_SCHEMA;
        } else if (node.isInt()) {
            return Schema.OPTIONAL_INT32_SCHEMA;
        } else if (node.isLong()) {
            return Schema.OPTIONAL_INT64_SCHEMA;
        } else if (node.isBoolean()) {
            return Schema.OPTIONAL_BOOLEAN_SCHEMA;
        } else if (node.isDouble() || node.isFloat()) {
            return Schema.OPTIONAL_FLOAT64_SCHEMA;
        } else if (node.isObject()) {
            return buildSchema(node); // 재귀적으로 처리
        } else if (node.isArray()) {
            if (node.isEmpty() || node.get(0).isNull()) {
                return SchemaBuilder.array(Schema.OPTIONAL_STRING_SCHEMA).optional().build();
            }
            JsonNode firstElement = node.get(0);
            Schema elementSchema = inferSchema(firstElement);
            return SchemaBuilder.array(elementSchema).optional().build();
        } else if (node.isNull()) {
            return Schema.OPTIONAL_STRING_SCHEMA; // 기본 타입
        } else {
            return Schema.OPTIONAL_STRING_SCHEMA;
        }
    }

    private Struct buildStruct(JsonNode jsonNode, Schema schema) {
        Struct struct = new Struct(schema);
        for (Field field : schema.fields()) {
            JsonNode valueNode = jsonNode.get(field.name());
            if (valueNode == null || valueNode.isNull()) {
                struct.put(field.name(), null);
            } else {
                struct.put(field.name(), extractValue(valueNode, field.schema()));
            }
        }
        return struct;
    }

    private Object extractValue(JsonNode node, Schema schema) {
        switch (schema.type()) {
            case STRING:
                return node.asText();
            case INT32:
                return node.asInt();
            case INT64:
                return node.asLong();
            case BOOLEAN:
                return node.asBoolean();
            case FLOAT64:
                return node.asDouble();
            case STRUCT:
                return buildStruct(node, schema);
            case ARRAY:
                if (!node.isArray()) return null;
                List<Object> values = new ArrayList<>();
                Schema itemSchema = schema.valueSchema();  // 요소 스키마 사용

                for (JsonNode element : node) {
                    values.add(extractValue(element, itemSchema));
                }
                return values;
            default:
                return null;
        }
    }

    @Override
    public ConfigDef config() {
        return new ConfigDef();
    }

    @Override
    public void configure(Map<String, ?> configs) {}

    @Override
    public void close() {}
}
