package gov.nasa.jpl.aerie.contrib.serialization.mappers;

import gov.nasa.jpl.aerie.merlin.framework.Result;
import gov.nasa.jpl.aerie.merlin.framework.ValueMapper;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;

import java.util.Arrays;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class EnumValueMapper<E extends Enum<E>> implements ValueMapper<E> {
    private final Class<E> enumType;

    public EnumValueMapper(Class<E> enumType) {
        this.enumType = enumType;
    }

    @Override
    public ValueSchema getValueSchema() {
        final var variants = Arrays
            .stream(this.enumType.getEnumConstants())
            .map(c -> new ValueSchema.Variant(c.name(), c.toString()))
            .collect(Collectors.toUnmodifiableList());

        return ValueSchema.ofVariant(variants);
    }

    @Override
    public Result<E, String> deserializeValue(SerializedValue serializedValue) {
        return serializedValue
                .asString()
                .map((Function<String, Result<String, String>>) Result::success)
                .orElseGet(() -> Result.failure("Expected string, got: "))
                .match(this::deserializeEnumValue, Result::failure);
    }

    @Override
    public SerializedValue serializeValue(E value) {
        return SerializedValue.of(value.name());
    }

    private Result<E, String> deserializeEnumValue(String name) {
        try {
            return Result.success(Enum.valueOf(enumType, name));
        } catch (IllegalArgumentException e) {
            return Result.failure(String.format("%s is not a valid value for enum %s", name, enumType.getCanonicalName()));
        }
    }
}
