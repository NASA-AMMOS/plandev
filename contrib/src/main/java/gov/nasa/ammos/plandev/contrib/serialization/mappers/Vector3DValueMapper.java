package gov.nasa.jpl.aerie.contrib.serialization.mappers;

import gov.nasa.jpl.aerie.merlin.framework.Result;
import gov.nasa.jpl.aerie.merlin.framework.ValueMapper;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import org.apache.commons.math3.geometry.euclidean.threed.Vector3D;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

public final class Vector3DValueMapper implements ValueMapper<Vector3D> {
  private final ValueMapper<Double> componentMapper;

  public Vector3DValueMapper(final ValueMapper<Double> componentMapper) {
    this.componentMapper = Objects.requireNonNull(componentMapper);
  }

  @Override
  public ValueSchema getValueSchema() {
    return ValueSchema.ofSeries(this.componentMapper.getValueSchema());
  }

  @Override
  public Result<Vector3D, String> deserializeValue(final SerializedValue serializedValue) {
    return serializedValue
        .asList()
        .map((Function<List<SerializedValue>, Result<List<SerializedValue>, String>>) Result::success)
        .orElseGet(() -> Result.failure("Expected list, got " + serializedValue.toString()))
        .match(
            serializedElements -> {
              if (serializedElements.size() != 3) return Result.failure("Expected 3 components, got " + serializedElements.size());
              final var components = new double[3];
              for (int i=0; i<3; i++) {
                final var result = this.componentMapper.deserializeValue(serializedElements.get(i));
                if (result.getKind() == Result.Kind.Failure) return result.mapSuccess(_left -> null);

                // SAFETY: `result` must be a Success variant.
                components[i] = result.getSuccessOrThrow();
              }
              return Result.success(new Vector3D(components));
            },
            Result::failure
        );
  }

  @Override
  public SerializedValue serializeValue(final Vector3D value) {
    return SerializedValue.of(List.of(
        this.componentMapper.serializeValue(value.getX()),
        this.componentMapper.serializeValue(value.getY()),
        this.componentMapper.serializeValue(value.getZ())));
  }
}
