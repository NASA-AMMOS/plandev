package gov.nasa.jpl.aerie.merlin.framework;

import gov.nasa.jpl.aerie.merlin.protocol.driver.Initializer;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Querier;
import gov.nasa.jpl.aerie.merlin.protocol.model.OutputType;
import gov.nasa.jpl.aerie.merlin.protocol.types.RealDynamics;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.UnaryOperator;

public final class Registrar {
  private final Initializer builder;

  public Registrar(final Initializer builder) {
    this.builder = Objects.requireNonNull(builder);
  }

  public boolean isInitializationComplete() {
    return (ModelActions.context.get().getContextType() != Context.ContextType.Initializing);
  }

  public <Value> void discrete(final String name, final Resource<Value> resource, final ValueMapper<Value> mapper) {
    this.builder.resource(name, makeResource("discrete", resource, mapper.getValueSchema(), mapper::serializeValue, null));
  }

  public <Value> void discrete(final String name, final Resource<Value> resource, final ValueMapper<Value> mapper, final String description) {
    this.builder.resource(name, makeResource("discrete", resource, mapper.getValueSchema(), mapper::serializeValue, description));
  }

  public void real(final String name, final Resource<RealDynamics> resource) {
    real(name, resource, $ -> $);
  }

  public void real(final String name, final Resource<RealDynamics> resource, final String description) {
    real(name, resource, $ -> $, description);
  }

  public <T> void realWithMetadata(final String name, final Resource<RealDynamics> resource, final String key, final T metadata, final ValueMapper<T> metadataValueMapper) {
    realWithMetadata(name, resource, key, metadata, metadataValueMapper, null);
  }

  public <T> void realWithMetadata(final String name, final Resource<RealDynamics> resource, final String key, final T metadata, final ValueMapper<T> metadataValueMapper, String description) {
    real(name, resource, $ -> ValueSchema.withMeta(key, metadataValueMapper.serializeValue(metadata), $), description);
  }

  private void real(final String name, final Resource<RealDynamics> resource, UnaryOperator<ValueSchema> schemaModifier) {
    this.builder.resource(
        name,
        makeResource(
            "real",
            resource,
            schemaModifier.apply(ValueSchema.ofStruct(Map.of(
                "initial", ValueSchema.REAL,
                "rate", ValueSchema.REAL))),
            dynamics -> SerializedValue.of(Map.of(
                "initial", SerializedValue.of(dynamics.initial),
                "rate", SerializedValue.of(dynamics.rate))),
            null
        ));
  }

  private void real(final String name, final Resource<RealDynamics> resource, UnaryOperator<ValueSchema> schemaModifier, final String description) {
    this.builder.resource(
        name,
        makeResource(
            "real",
            resource,
            schemaModifier.apply(ValueSchema.ofStruct(Map.of(
                "initial", ValueSchema.REAL,
                "rate", ValueSchema.REAL))),
            dynamics -> SerializedValue.of(Map.of(
                "initial", SerializedValue.of(dynamics.initial),
                "rate", SerializedValue.of(dynamics.rate))),
            description
            ));
  }

  private static <Value> gov.nasa.jpl.aerie.merlin.protocol.model.Resource<Value> makeResource(
      final String type,
      final Resource<Value> resource,
      final ValueSchema valueSchema,
      final Function<Value, SerializedValue> serializer,
      final String description
  ) {
    return new gov.nasa.jpl.aerie.merlin.protocol.model.Resource<>() {
      @Override
      public String getType() {
        return type;
      }

      @Override
      public OutputType<Value> getOutputType() {
        return new OutputType<>() {
          @Override
          public ValueSchema getSchema() {
            if (description != null) {
              return ValueSchema.withMeta("description", SerializedValue.of(Map.of("value", SerializedValue.of(description))), valueSchema);
            }
            return valueSchema;
          }

          @Override
          public SerializedValue serialize(final Value value) {
            return serializer.apply(value);
          }
        };
      }

      @Override
      public Value getDynamics(final Querier querier) {
        try (final var _token = ModelActions.context.set(new QueryContext(querier))) {
          return resource.getDynamics();
        }
      }
    };
  }

  public <Event> void topic(final String name, final CellRef<Event,?> ref, final ValueMapper<Event> mapper) {
    Objects.requireNonNull(mapper);

    this.builder.topic(name, ref.topic, new OutputType<>() {
      @Override
      public ValueSchema getSchema() {
        return mapper.getValueSchema();
      }

      @Override
      public SerializedValue serialize(final Event value) {
        return mapper.serializeValue(value);
      }
    });
  }
}
