package gov.nasa.jpl.aerie.e2e.types;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

public sealed interface ValueSchema {
  // Constants for the Value Schemas that take no parameters
  ValueSchemaBoolean VALUE_SCHEMA_BOOLEAN = new ValueSchemaBoolean();
  ValueSchemaDuration VALUE_SCHEMA_DURATION = new ValueSchemaDuration();
  ValueSchemaInt VALUE_SCHEMA_INT = new ValueSchemaInt();
  ValueSchemaPath VALUE_SCHEMA_PATH = new ValueSchemaPath();
  ValueSchemaReal VALUE_SCHEMA_REAL = new ValueSchemaReal();
  ValueSchemaString VALUE_SCHEMA_STRING = new ValueSchemaString();

  static ValueSchema fromJSON(ObjectNode json) {
    final var result = switch (json.get("type").textValue()) {
      case "boolean" -> VALUE_SCHEMA_BOOLEAN;
      case "duration" -> VALUE_SCHEMA_DURATION;
      case "int" -> VALUE_SCHEMA_INT;
      case "path" -> VALUE_SCHEMA_PATH;
      case "real" -> VALUE_SCHEMA_REAL;
      case "series" -> new ValueSchemaSeries(ValueSchema.fromJSON(json.get("items")));
      case "string" -> VALUE_SCHEMA_STRING;
      case "struct" -> {
        final var items = new HashMap<String, ValueSchema>();
        final var itemsJson = json.get("items");
        final var fieldIter = itemsJson.fieldNames();
        while (fieldIter.hasNext()) {
          final var item = fieldIter.next();
          items.put(item, ValueSchema.fromJSON((ObjectNode) itemsJson.get(item)));
        }
        yield new ValueSchemaStruct(items);
      }
      case "variant" -> {
        final var variantsArr = json.get("variants");
        final var variants = StreamSupport.stream(variantsArr.spliterator(), false)
            .map(v -> new Variant(v.get("key").textValue(), v.get("label").textValue()))
            .toList();
        yield new ValueSchemaVariant(variants);
      }
      default -> throw new IllegalArgumentException("Cannot determine ValueSchema from JSON");
    };
    if (json.has("metadata")) {
      final var metadataNode = json.get("metadata");
      final var metadata = new HashMap<String, JsonNode>();
      metadataNode.fields().forEachRemaining(e -> metadata.put(e.getKey(), e.getValue()));
      return new ValueSchemaMeta(metadata, result);
    } else {
      return result;
    }
  }

  ObjectNode toJson();

  record ValueSchemaBoolean() implements ValueSchema {
    @Override
    public ObjectNode toJson() {
      return JsonNodeFactory.instance.objectNode().put("type", "boolean");
    }
  }

  record ValueSchemaDuration() implements ValueSchema {
    @Override
    public ObjectNode toJson() {
      return JsonNodeFactory.instance.objectNode().put("type", "duration");
    }
  }

  record ValueSchemaInt() implements ValueSchema {
    @Override
    public ObjectNode toJson() {
      return JsonNodeFactory.instance.objectNode().put("type", "int");
    }
  }

  record ValueSchemaPath() implements ValueSchema {
    @Override
    public ObjectNode toJson() {
      return JsonNodeFactory.instance.objectNode().put("type", "path");
    }
  }

  record ValueSchemaReal() implements ValueSchema {
    @Override
    public ObjectNode toJson() {
      return JsonNodeFactory.instance.objectNode().put("type", "real");
    }
  }

  record ValueSchemaSeries(ValueSchema items) implements ValueSchema {
    @Override
    public ObjectNode toJson() {
      return JsonNodeFactory.instance.objectNode()
                 .put("type", "series")
                 .set("items", items.toJson())
                 ;
    }
  }

  record ValueSchemaString() implements ValueSchema {
    @Override
    public ObjectNode toJson() {
      return JsonNodeFactory.instance.objectNode().put("type", "string");
    }
  }

  record ValueSchemaStruct(Map<String, ValueSchema> items) implements ValueSchema {
    @Override
    public boolean equals(Object o){
      if (!(o instanceof final ValueSchemaStruct other)) return false;

      if(this.items.size() != other.items.size()) return false;
      for(final var itemKey : this.items().keySet()){
        if(!other.items.has(itemKey)) return false;
        if(!this.items.get(itemKey).equals(other.items.get(itemKey))) return false;
      }
      return true;
    }

    @Override
    public ObjectNode toJson() {
      final var itemsBuilder = JsonNodeFactory.instance.objectNode();
      items.forEach((k, v) -> itemsBuilder.set(k, v.toJson()));

      return JsonNodeFactory.instance.objectNode()
                 .put("type", "struct")
                 .set("items", itemsBuilder)
                 ;
    }
  }

  record ValueSchemaVariant(List<Variant> variants) implements ValueSchema {
    @Override
    public ObjectNode toJson() {
      final var variantsBuilder = JsonNodeFactory.instance.arrayNode();
      variants.forEach(v -> variantsBuilder.add(v.toJson()));

      return JsonNodeFactory.instance.objectNode()
                 .put("type", "variant")
                 .set("variants", variantsBuilder)
                 ;
    }
  }

  record ValueSchemaMeta(Map<String, JsonNode> metadata, ValueSchema target) implements ValueSchema {
    @Override
    public ObjectNode toJson() {
      final var builder = ((ObjectNode) target.toJson().deepCopy());
      metadata.forEach(builder::set);
      return builder;
    }
  }

  record Variant(String key, String label) {
    public ObjectNode toJson() {
      return JsonNodeFactory.instance.objectNode().put("key", key).put("label", label);
    }
  }
}
