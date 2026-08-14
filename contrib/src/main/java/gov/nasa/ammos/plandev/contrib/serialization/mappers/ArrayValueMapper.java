package gov.nasa.jpl.aerie.contrib.serialization.mappers;

import gov.nasa.jpl.aerie.merlin.framework.Result;
import gov.nasa.jpl.aerie.merlin.framework.ValueMapper;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;

import java.lang.reflect.Array;
import java.util.ArrayList;

public final class ArrayValueMapper<T> implements ValueMapper<T[]> {
    private final ValueMapper<T> elementMapper;
    private final Class<? super T> elementClass;

    public ArrayValueMapper(final ValueMapper<T> elementMapper, Class<? super T> elementClass) {
        this.elementMapper = elementMapper;
        this.elementClass = elementClass;
    }

    @Override
    public ValueSchema getValueSchema() {
        return ValueSchema.ofSeries(elementMapper.getValueSchema());
    }

    @Override
    public Result<T[], String> deserializeValue(SerializedValue serializedValue) {
        final var list = serializedValue.asList().get(); // TODO: Could fail, should fix

        @SuppressWarnings("unchecked")
        final var arr = (T[]) Array.newInstance(this.elementClass, list.size());

        for (int i=0; i<list.size(); i++) {
            arr[i] = this.elementMapper.deserializeValue(list.get(i)).getSuccessOrThrow();
        }

        return Result.success(arr);
    }

    @Override
    public SerializedValue serializeValue(T[] value) {
        final var serializedElements = new ArrayList<SerializedValue>();
        for (final var element : value) {
            serializedElements.add(this.elementMapper.serializeValue(element));
        }
        return SerializedValue.of(serializedElements);
    }
}
