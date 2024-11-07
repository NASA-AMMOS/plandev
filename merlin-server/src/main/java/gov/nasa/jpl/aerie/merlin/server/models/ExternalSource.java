package gov.nasa.jpl.aerie.merlin.server.models;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;

import java.time.Instant;
import java.util.Date;
import java.util.List;
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

  public boolean meow(Map<String, ValueSchema> propertySchemas) {
    if (properties.size() != propertySchemas.size()) {
      return false;
    }
    if (!propertySchemas.keySet().containsAll(properties.keySet())) {
      return false;
    }

    var result = true;
    for (String propName : properties.keySet()) {
      SerializedValue s = properties.get(propName);
      ValueSchema v = propertySchemas.get(propName);

      result &= bowwow(v, s);
    }
    return result;
  }

  private static boolean bowwow(ValueSchema v, SerializedValue s) {
    return switch (v) {
      case ValueSchema.BooleanSchema booleanSchema -> {
        yield s.asBoolean().isPresent();
      }
      case ValueSchema.DurationSchema durationSchema -> {
//        if (s.asInt().isPresent()) {
//          yield Duration.of(s.asInt().get(), Duration.MICROSECOND) instanceof Duration;
//        }
//        yield false;
        yield s.asInt().isPresent();
      }
      case ValueSchema.IntSchema intSchema -> {
        yield s.asInt().isPresent();
      }
      case ValueSchema.RealSchema realSchema -> {
        yield s.asReal().isPresent();
      }
      case ValueSchema.SeriesSchema seriesSchema -> {
        if(s.asList().isPresent()) {
          var result = true;
          ValueSchema v_sub = v.asSeries().get();
          for (var s_sub : s.asList().get()) {
            result &= bowwow(v_sub, s_sub);
          }
          yield result;
        }
        yield false;
      }
      case ValueSchema.StringSchema stringSchema -> {
        yield s.asString().isPresent();
      }
      case ValueSchema.StructSchema structSchema -> {
        if (s.asMap().isPresent()) {
          var result = true;
          for (String propName : s.asMap().get().keySet()) {
            SerializedValue s_sub = s.asMap().get().get(propName);
            ValueSchema v_sub = v.asStruct().get().get(propName);

            result &= bowwow(v_sub, s_sub);
          }
          yield result;
        }
        yield false;
      }
      case ValueSchema.VariantSchema variantSchema -> {
        yield s.asString().isPresent() &&
              variantSchema.variants().stream().map(ValueSchema.Variant::key).toList().contains(s.asString().get());
      }
      default -> {
        // pathSchema, metaSchema
        yield false;
      }
    };
  }
}
