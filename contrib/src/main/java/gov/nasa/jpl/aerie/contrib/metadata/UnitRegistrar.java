package gov.nasa.jpl.aerie.contrib.metadata;

import gov.nasa.jpl.aerie.merlin.framework.MetadataValueMapper;
import gov.nasa.jpl.aerie.merlin.framework.Registrar;
import gov.nasa.jpl.aerie.merlin.framework.Resource;
import gov.nasa.jpl.aerie.merlin.framework.Result;
import gov.nasa.jpl.aerie.merlin.framework.ValueMapper;
import gov.nasa.jpl.aerie.merlin.protocol.types.RealDynamics;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;

import java.util.Map;

public final class UnitRegistrar {
  public static <T> ValueMapper<T> withUnit(final String unit, final ValueMapper<T> target) {
    return new MetadataValueMapper<>("unit", SerializedValue.of(Map.of("value", SerializedValue.of(unit))), target);
  }
  public static <T> void discreteResource(final Registrar registrar, final String name, final Resource<T> resource, final ValueMapper<T> valueMapper, final String unit) {
    registrar.discrete(name, resource, withUnit(unit, valueMapper));
  }

  public static <T> void discreteResource(final Registrar registrar, final String name, final Resource<T> resource, final ValueMapper<T> valueMapper, final String unit, final String description) {
    registrar.discrete(name, resource, withUnit(unit, valueMapper), description);
  }

  public static void realResource(final Registrar registrar, final String name, final Resource<RealDynamics> resource, final String unit) {
    realResource(registrar, name, resource, unit, null);
  }

  public static void realResource(final Registrar registrar, final String name, final Resource<RealDynamics> resource, final String unit, final String description) {
    registrar.realWithMetadata(name, resource, "unit", unit, new ValueMapper<>() {
      @Override
      public ValueSchema getValueSchema() {
        return ValueSchema.ofStruct(Map.of("value", ValueSchema.STRING));
      }

      @Override
      public Result<String, String> deserializeValue(final SerializedValue serializedValue) {
        return serializedValue
            .asMap()
            .flatMap($ -> $.get("value").asString())
            .map(Result::<String, String>success)
            .orElse(Result.failure("Could not deserialize Unit"));
      }

      @Override
      public SerializedValue serializeValue(final String value) {
        return SerializedValue.of(Map.of("value", SerializedValue.of(value)));
      }
    }, description);
  }
}
