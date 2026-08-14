package gov.nasa.jpl.aerie.contrib.serialization.mappers;

import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecordValueMapperTest {

  record EmptyRecord() {}

  record StringRecord(String aString) {}

  record MultiValueRecord(String aString, Map<String, List<Boolean>> fancy) {}
  record NestedMultiValueRecord(String aString, Map<String, List<Boolean>> fancy, MultiValueRecord record) {}

  @Test
  void getValueSchema_emptyRecord() {
    final var valueSchema = new RecordValueMapper<>(EmptyRecord.class, List.of()).getValueSchema();
    assertEquals(ValueSchema.ofStruct(Map.of()), valueSchema);
  }

  @Test
  void getValueSchema_stringRecord() {
    final var valueSchema = new RecordValueMapper<>(
        StringRecord.class,
        List.of(new RecordValueMapper.Component<>(
            "aString",
            StringRecord::aString,
            new StringValueMapper()
        ))).getValueSchema();
    assertEquals(ValueSchema.withMeta(
        "item_order",
        SerializedValue.of(List.of(SerializedValue.of("aString"))),
        ValueSchema.ofStruct(Map.of("aString", ValueSchema.STRING))),
                 valueSchema);

  }

  final RecordValueMapper<MultiValueRecord> getMultiValueRecordValueMapper() {
    return new RecordValueMapper<>(
        MultiValueRecord.class,
        List.of(
            new RecordValueMapper.Component<>(
                "aString",
                MultiValueRecord::aString,
                new StringValueMapper()
            ),
            new RecordValueMapper.Component<>(
                "fancy",
                MultiValueRecord::fancy,
                new MapValueMapper<>(new StringValueMapper(), new ListValueMapper<>(new BooleanValueMapper()))
            )));
  }

  @Test
  void getValueSchema_multiValueRecord() {
    final var valueSchema = getMultiValueRecordValueMapper().getValueSchema();
    assertEquals(
        ValueSchema.withMeta(
            "item_order",
            SerializedValue.of(List.of(SerializedValue.of("aString"), SerializedValue.of("fancy"))),
            ValueSchema.ofStruct(Map.of(
                "aString", ValueSchema.STRING,
                "fancy", ValueSchema.ofSeries(ValueSchema.ofStruct(Map.of(
                    "key", ValueSchema.STRING,
                    "value", ValueSchema.ofSeries(ValueSchema.BOOLEAN))))))),
        valueSchema);
  }

  @Test
  void getValueSchema_nested() {
    final var valueSchema = new RecordValueMapper<>(
        NestedMultiValueRecord.class,
        List.of(
            new RecordValueMapper.Component<>(
                "aString",
                NestedMultiValueRecord::aString,
                new StringValueMapper()
            ),
            new RecordValueMapper.Component<>(
                "fancy",
                NestedMultiValueRecord::fancy,
                new MapValueMapper<>(new StringValueMapper(), new ListValueMapper<>(new BooleanValueMapper()))
            ),
            new RecordValueMapper.Component<>(
                "record",
                NestedMultiValueRecord::record,
                getMultiValueRecordValueMapper()
            ))).getValueSchema();
    assertEquals(
        ValueSchema.withMeta(
            "item_order",
            SerializedValue.of(List.of(
                SerializedValue.of("aString"),
                SerializedValue.of("fancy"),
                SerializedValue.of("record"))),
            ValueSchema.ofStruct(Map.of(
                "aString", ValueSchema.STRING,
                "fancy", ValueSchema.ofSeries(ValueSchema.ofStruct(Map.of(
                    "key", ValueSchema.STRING,
                    "value", ValueSchema.ofSeries(ValueSchema.BOOLEAN)))),
                "record", ValueSchema.withMeta(
                    "item_order",
                    SerializedValue.of(List.of(SerializedValue.of("aString"), SerializedValue.of("fancy"))),
                    ValueSchema.ofStruct(Map.of(
                        "aString", ValueSchema.STRING,
                        "fancy", ValueSchema.ofSeries(ValueSchema.ofStruct(Map.of(
                            "key", ValueSchema.STRING,
                            "value", ValueSchema.ofSeries(ValueSchema.BOOLEAN)))))))))),
        valueSchema);
  }

  @Test
  void deserializeValue_emptyRecord() {
    final var deserializedValue = new RecordValueMapper<>(EmptyRecord.class, List.of())
        .deserializeValue(SerializedValue.of(Map.of())).getSuccessOrThrow();
    assertEquals(new EmptyRecord(), deserializedValue);
  }

  @Test
  void deserializeValue_stringRecord() {
    final var deserializedValue = new RecordValueMapper<>(
        StringRecord.class,
        List.of(new RecordValueMapper.Component<>(
            "aString",
            StringRecord::aString,
            new StringValueMapper()
        )))
        .deserializeValue(SerializedValue.of(Map.of("aString", SerializedValue.of("hello world"))))
        .getSuccessOrThrow();
    assertEquals(new StringRecord("hello world"), deserializedValue);
  }

  @Test
  void deserializeValue_multiValueRecord() {
    final var deserializedValue = new RecordValueMapper<>(
        MultiValueRecord.class,
        List.of(
            new RecordValueMapper.Component<>(
                "aString",
                MultiValueRecord::aString,
                new StringValueMapper()
            ),
            new RecordValueMapper.Component<>(
                "fancy",
                MultiValueRecord::fancy,
                new MapValueMapper<>(new StringValueMapper(), new ListValueMapper<>(new BooleanValueMapper()))
            ))).deserializeValue(SerializedValue.of(Map.of(
        "aString", SerializedValue.of("one small step for crayon"),
        "fancy", SerializedValue.of(List.of(
            SerializedValue.of(Map.of(
                "key", SerializedValue.of("x"),
                "value", SerializedValue.of(List.of(SerializedValue.of(false))
                ))),
            SerializedValue.of(Map.of(
                "key", SerializedValue.of("y"),
                "value", SerializedValue.of(List.of(SerializedValue.of(true))
                )))))
    ))).getSuccessOrThrow();

    assertEquals(
        new MultiValueRecord("one small step for crayon", Map.of("x", List.of(false), "y", List.of(true))),
        deserializedValue);
  }

  @Test
  void serializeValue_emptyRecord() {
    final var serializedValue = new RecordValueMapper<>(EmptyRecord.class, List.of())
        .serializeValue(new EmptyRecord());
    assertEquals(SerializedValue.of(Map.of()), serializedValue);
  }

  @Test
  void serializeValue_stringRecord() {
    final var serializedValue = new RecordValueMapper<>(
        StringRecord.class,
        List.of(new RecordValueMapper.Component<>(
            "aString",
            StringRecord::aString,
            new StringValueMapper()
        ))).serializeValue(new StringRecord("hello world"));
    assertEquals(SerializedValue.of(Map.of("aString", SerializedValue.of("hello world"))), serializedValue);
  }

  @Test
  void serializeValue_multiValueRecord() {
    final var serializedValue = new RecordValueMapper<>(
        MultiValueRecord.class,
        List.of(
            new RecordValueMapper.Component<>(
                "aString",
                MultiValueRecord::aString,
                new StringValueMapper()
            ),
            new RecordValueMapper.Component<>(
                "fancy",
                MultiValueRecord::fancy,
                new MapValueMapper<>(new StringValueMapper(), new ListValueMapper<>(new BooleanValueMapper()))
            ))).serializeValue(new MultiValueRecord(
        "one small step for crayon",
        Map.of("x", List.of(false), "y", List.of(true))));
    final var permutation1 = SerializedValue.of(List.of(
        SerializedValue.of(Map.of(
            "key", SerializedValue.of("x"),
            "value", SerializedValue.of(List.of(SerializedValue.of(false))
            ))),
        SerializedValue.of(Map.of(
            "key", SerializedValue.of("y"),
            "value", SerializedValue.of(List.of(SerializedValue.of(true))
            )))));
    final var permutation2 = SerializedValue.of(List.of(
        SerializedValue.of(Map.of(
            "key", SerializedValue.of("y"),
            "value", SerializedValue.of(List.of(SerializedValue.of(true))
            ))),
        SerializedValue.of(Map.of(
            "key", SerializedValue.of("x"),
            "value", SerializedValue.of(List.of(SerializedValue.of(false))
            )))));
    if (SerializedValue.of(Map.of(
        "aString", SerializedValue.of("one small step for crayon"),
        "fancy", permutation1)).equals(serializedValue)
        ||
        SerializedValue.of(Map.of(
        "aString", SerializedValue.of("one small step for crayon"),
        "fancy", permutation2)).equals(serializedValue)) {
      // If either permutation matches, this test has passed. This is to allow for non-deterministic ordering of the map.
    } else {
      // This assert is guaranteed to fail, since both equality checks above have failed. We use assertEquals instead
      // of fail because we want the nice diff.
      assertEquals(SerializedValue.of(Map.of(
          "aString", SerializedValue.of("one small step for crayon"),
          "fancy", permutation1)), serializedValue);
    }
  }
}
