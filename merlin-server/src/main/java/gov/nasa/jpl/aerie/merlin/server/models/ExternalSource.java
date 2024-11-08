package gov.nasa.jpl.aerie.merlin.server.models;

import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;

import java.time.Instant;
import java.util.Map;

//record SourcePeriod(
//    Date startTime,
//    Date endTIme
//) {}

//record SourceData(
//    String key,
//    String sourceType,
//    Date validAt
////    SourcePeriod period
//) {}

//record EventData(
//    String key,
//    String eventType,
//    Date startTime,
//    Duration duration
//) {}

public record ExternalSource(
//    SourceData source
//    List<EventData> events
    String key,
    String sourceType,
    Instant validAt,
    Map<String, SerializedValue> properties
) {

  public sealed interface Result {
    record Success() implements Result {}
    record Error(String failureMessage) implements Result {}
  }

  public Result parseProperties(Map<String, ValueSchema> propertySchemas) {
    if (properties.size() != propertySchemas.size()) {
      var propertiesString = properties.keySet().stream().reduce("", (result, element) -> result + element);
      var propertySchemaString = propertySchemas.keySet().stream().reduce("", (result, element) -> result + element);
      return new Result.Error("Too many or too few properties included.\nProperties: " + propertiesString + "\nPropertySchemas: " + propertySchemaString);
    }
    if (!propertySchemas.keySet().containsAll(properties.keySet())) {
      var propertiesString = properties.keySet().stream().reduce("", (result, element) -> result + element);
      var propertySchemaString = propertySchemas.keySet().stream().reduce("", (result, element) -> result + element);
      return new Result.Error("Incorrect properties included.\nProperties: " + propertiesString + "\nPropertySchemas: " + propertySchemaString);

    }

    final var basePath = "ExternalSource/properties/";
    for (String propName : properties.keySet()) {
      SerializedValue s = properties.get(propName);
      ValueSchema v = propertySchemas.get(propName);

      var result = parsePropertiesRec(v, s, basePath + propName + "/");
      switch (result) {
          case Result.Success() -> {
          }
          case Result.Error(String ignored) -> {
          return result;
        }
      }
    }
    return new Result.Success();
  }

  private static Result parsePropertiesRec(ValueSchema v, SerializedValue s, String currentPath) {
    return switch (v) {
      case ValueSchema.BooleanSchema booleanSchema -> {
        if (!s.asBoolean().isPresent()) {
          yield new Result.Error("Failed at path " + currentPath + ". Expected type boolean, but got value " + s);
        }
        yield new Result.Success();
      }
      case ValueSchema.DurationSchema durationSchema -> {
        if (!s.asInt().isPresent()) {
          yield new Result.Error("Failed at path " + currentPath + ". Expected type duration (int-like), but got value " + s);
        }
        yield new Result.Success();
      }
      case ValueSchema.IntSchema intSchema -> {
        if (!s.asInt().isPresent()) {
          yield new Result.Error("Failed at path " + currentPath + ". Expected type int, but got value " + s);
        }
        yield new Result.Success();
      }
      case ValueSchema.RealSchema realSchema -> {
        if (!s.asReal().isPresent()) {
          yield new Result.Error("Failed at path " + currentPath + ". Expected type real, but got value " + s);
        }
        yield new Result.Success();
      }
      case ValueSchema.SeriesSchema seriesSchema -> {
        if(!s.asList().isPresent()) {
          yield new Result.Error("Failed at path " + currentPath + ". Expected type series, but got value " + s);
        }
        else {
          ValueSchema v_sub = v.asSeries().get();
          for (var s_sub : s.asList().get()) {
            var result = parsePropertiesRec(v_sub, s_sub, currentPath + "[LIST]" + "/");
            switch (result) {
              case Result.Success() -> {
              }
              case Result.Error(String failureMessage) -> {
                yield result;
              }
            }
          }
        }
        yield new Result.Success();
      }
      case ValueSchema.StringSchema stringSchema -> {
        if (!s.asString().isPresent()) {
          yield new Result.Error("Failed at path " + currentPath + ". Expected type string, but got value " + s);
        }
        yield new Result.Success();
      }
      case ValueSchema.StructSchema structSchema -> {
        if(!s.asMap().isPresent()) {
          yield new Result.Error("Failed at path " + currentPath + ". Expected type struct, but got value " + s);
        }
        else {
          for (String propName : s.asMap().get().keySet()) {
            SerializedValue s_sub = s.asMap().get().get(propName);
            ValueSchema v_sub = v.asStruct().get().get(propName);

            var result = parsePropertiesRec(v_sub, s_sub, currentPath + propName + "/");
            switch(result) {
              case Result.Success() -> {
              }
              case Result.Error(String failureMessage) -> {
                yield result;
              }
            }
          }
        }
        yield new Result.Success();
      }
      case ValueSchema.VariantSchema variantSchema -> {
        var variants = variantSchema.variants().stream().map(ValueSchema.Variant::key).toList();
        var correct = s.asString().isPresent() && variants.contains(s.asString().get());
        if (!correct) {
          var variantString = variants.stream().reduce("", (result, element) -> result + ", " + element);
          yield new Result.Error("Failed at path " + currentPath + ". Expected type variant [" + variantString + "], but got value " + s);
        }
        yield new Result.Success();
      }
      default -> {
        // pathSchema, metaSchema
        // TODO: PATH
        yield new Result.Error("Encountered unexpected schema at path");
      }
    };
  }
}
