package gov.nasa.jpl.aerie.banananation.activities;

import gov.nasa.jpl.aerie.banananation.generated.activities.ParameterTestActivityMapper;
import gov.nasa.jpl.aerie.contrib.serialization.mappers.DurationValueMapper;
import gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ParameterTestActivityTest {
  private final ParameterTestActivityMapper mapper;

  public ParameterTestActivityTest() {
    this.mapper = new ParameterTestActivityMapper();
  }

  @Test
  public void testDefaultSerializationDoesNotThrow() {
    this.mapper.getInputType().getArguments(new ParameterTestActivity());
  }

  @Test
  public void testDeserialization() throws InstantiationException {
    final Map<String, SerializedValue> sourceActivity = createSerializedArguments();
    final ParameterTestActivity testValues = new ParameterTestActivity();

    final ParameterTestActivity deserializedActivity = this.mapper.getInputType().instantiate(sourceActivity);

    // Verify the deserialized activity contains the expected values
    assertEquals(deserializedActivity.primitiveDouble, testValues.primitiveDouble, 0.0);
    assertEquals(deserializedActivity.primitiveFloat, testValues.primitiveFloat, 0.0);
    assertEquals(deserializedActivity.primitiveByte, testValues.primitiveByte);
    assertEquals(deserializedActivity.primitiveShort, testValues.primitiveShort);
    assertEquals(deserializedActivity.primitiveInt, testValues.primitiveInt);
    assertEquals(deserializedActivity.primitiveLong, testValues.primitiveLong);
    assertEquals(deserializedActivity.primitiveChar, testValues.primitiveChar);
    assertEquals(deserializedActivity.primitiveBoolean, testValues.primitiveBoolean);
    assertEquals(deserializedActivity.boxedDouble, testValues.boxedDouble);
    assertEquals(deserializedActivity.boxedFloat, testValues.boxedFloat);
    assertEquals(deserializedActivity.boxedByte, testValues.boxedByte);
    assertEquals(deserializedActivity.boxedShort, testValues.boxedShort);
    assertEquals(deserializedActivity.boxedInt, testValues.boxedInt);
    assertEquals(deserializedActivity.boxedLong, testValues.boxedLong);
    assertEquals(deserializedActivity.boxedChar, testValues.boxedChar);
    assertEquals(deserializedActivity.boxedBoolean, testValues.boxedBoolean);
    assertEquals(deserializedActivity.string, testValues.string);
    assertArrayEquals(deserializedActivity.doubleArray, testValues.doubleArray);
    assertArrayEquals(deserializedActivity.floatArray, testValues.floatArray);
    assertArrayEquals(deserializedActivity.byteArray, testValues.byteArray);
    assertArrayEquals(deserializedActivity.shortArray, testValues.shortArray);
    assertArrayEquals(deserializedActivity.intArray, testValues.intArray);
    assertArrayEquals(deserializedActivity.longArray, testValues.longArray);
    assertArrayEquals(deserializedActivity.charArray, testValues.charArray);
    assertArrayEquals(deserializedActivity.booleanArray, testValues.booleanArray);
    assertArrayEquals(deserializedActivity.stringArray, testValues.stringArray);
    assertArrayEquals(deserializedActivity.primDoubleArray, testValues.primDoubleArray, 0);
    assertArrayEquals(deserializedActivity.primFloatArray, testValues.primFloatArray, 0);
    assertArrayEquals(deserializedActivity.primByteArray, testValues.primByteArray);
    assertArrayEquals(deserializedActivity.primShortArray, testValues.primShortArray);
    assertArrayEquals(deserializedActivity.primIntArray, testValues.primIntArray);
    assertArrayEquals(deserializedActivity.primLongArray, testValues.primLongArray);
    assertArrayEquals(deserializedActivity.primCharArray, testValues.primCharArray);
    assertArrayEquals(deserializedActivity.primBooleanArray, testValues.primBooleanArray);
    assertEquals(deserializedActivity.doubleList, testValues.doubleList);
    assertEquals(deserializedActivity.floatList, testValues.floatList);
    assertEquals(deserializedActivity.byteList, testValues.byteList);
    assertEquals(deserializedActivity.shortList, testValues.shortList);
    assertEquals(deserializedActivity.intList, testValues.intList);
    assertEquals(deserializedActivity.longList, testValues.longList);
    assertEquals(deserializedActivity.charList, testValues.charList);
    assertEquals(deserializedActivity.booleanList, testValues.booleanList);
    assertEquals(deserializedActivity.stringList, testValues.stringList);
    assertEquals(deserializedActivity.doubleMap, testValues.doubleMap);
    assertEquals(deserializedActivity.floatMap, testValues.floatMap);
    assertEquals(deserializedActivity.byteMap, testValues.byteMap);
    assertEquals(deserializedActivity.shortMap, testValues.shortMap);
    assertEquals(deserializedActivity.intMap, testValues.intMap);
    assertEquals(deserializedActivity.longMap, testValues.longMap);
    assertEquals(deserializedActivity.charMap, testValues.charMap);
    assertEquals(deserializedActivity.booleanMap, testValues.booleanMap);
    assertEquals(deserializedActivity.stringMap, testValues.stringMap);
    assertEquals(deserializedActivity.testDuration, testValues.testDuration);
    assertEquals(deserializedActivity.testEnum, testValues.testEnum);
    assertEquals(deserializedActivity.mappyBoi, testValues.mappyBoi);
    assertArrayEquals(deserializedActivity.doublePrimIntArray, testValues.doublePrimIntArray);
    assertArrayEquals(deserializedActivity.intListArrayArray, testValues.intListArrayArray);
    // TODO; Check equality for obnoxious (this is quite complex)
  }

  @Test
  public void testSerialization() throws InstantiationException {
    final ParameterTestActivity sourceActivity = new ParameterTestActivity();
    final Map<String, SerializedValue> activityArgs = this.mapper.getInputType().getArguments(sourceActivity);

    final ParameterTestActivity deserializedActivity = this.mapper.getInputType().instantiate(activityArgs);

    assertEquals(sourceActivity.primitiveDouble, deserializedActivity.primitiveDouble, 0.0);
    assertEquals(sourceActivity.primitiveFloat, deserializedActivity.primitiveFloat, 0.0);
    assertEquals(sourceActivity.primitiveByte, deserializedActivity.primitiveByte);
    assertEquals(sourceActivity.primitiveShort, deserializedActivity.primitiveShort);
    assertEquals(sourceActivity.primitiveInt, deserializedActivity.primitiveInt);
    assertEquals(sourceActivity.primitiveLong, deserializedActivity.primitiveLong);
    assertEquals(sourceActivity.primitiveChar, deserializedActivity.primitiveChar);
    assertEquals(sourceActivity.primitiveBoolean, deserializedActivity.primitiveBoolean);
    assertEquals(sourceActivity.boxedDouble, deserializedActivity.boxedDouble);
    assertEquals(sourceActivity.boxedFloat, deserializedActivity.boxedFloat);
    assertEquals(sourceActivity.boxedByte, deserializedActivity.boxedByte);
    assertEquals(sourceActivity.boxedShort, deserializedActivity.boxedShort);
    assertEquals(sourceActivity.boxedInt, deserializedActivity.boxedInt);
    assertEquals(sourceActivity.boxedLong, deserializedActivity.boxedLong);
    assertEquals(sourceActivity.boxedChar, deserializedActivity.boxedChar);
    assertEquals(sourceActivity.boxedBoolean, deserializedActivity.boxedBoolean);
    assertEquals(sourceActivity.string, deserializedActivity.string);
    assertArrayEquals(sourceActivity.doubleArray, deserializedActivity.doubleArray);
    assertArrayEquals(sourceActivity.floatArray, deserializedActivity.floatArray);
    assertArrayEquals(sourceActivity.byteArray, deserializedActivity.byteArray);
    assertArrayEquals(sourceActivity.shortArray, deserializedActivity.shortArray);
    assertArrayEquals(sourceActivity.intArray, deserializedActivity.intArray);
    assertArrayEquals(sourceActivity.longArray, deserializedActivity.longArray);
    assertArrayEquals(sourceActivity.charArray, deserializedActivity.charArray);
    assertArrayEquals(sourceActivity.booleanArray, deserializedActivity.booleanArray);
    assertArrayEquals(sourceActivity.stringArray, deserializedActivity.stringArray);
    assertArrayEquals(sourceActivity.primDoubleArray, deserializedActivity.primDoubleArray, 0);
    assertArrayEquals(sourceActivity.primFloatArray, deserializedActivity.primFloatArray, 0);
    assertArrayEquals(sourceActivity.primByteArray, deserializedActivity.primByteArray);
    assertArrayEquals(sourceActivity.primShortArray, deserializedActivity.primShortArray);
    assertArrayEquals(sourceActivity.primIntArray, deserializedActivity.primIntArray);
    assertArrayEquals(sourceActivity.primLongArray, deserializedActivity.primLongArray);
    assertArrayEquals(sourceActivity.primCharArray, deserializedActivity.primCharArray);
    assertArrayEquals(sourceActivity.primBooleanArray, deserializedActivity.primBooleanArray);
    assertEquals(sourceActivity.doubleList, deserializedActivity.doubleList);
    assertEquals(sourceActivity.floatList, deserializedActivity.floatList);
    assertEquals(sourceActivity.byteList, deserializedActivity.byteList);
    assertEquals(sourceActivity.shortList, deserializedActivity.shortList);
    assertEquals(sourceActivity.intList, deserializedActivity.intList);
    assertEquals(sourceActivity.longList, deserializedActivity.longList);
    assertEquals(sourceActivity.charList, deserializedActivity.charList);
    assertEquals(sourceActivity.booleanList, deserializedActivity.booleanList);
    assertEquals(sourceActivity.stringList, deserializedActivity.stringList);
    assertEquals(sourceActivity.doubleMap, deserializedActivity.doubleMap);
    assertEquals(sourceActivity.floatMap, deserializedActivity.floatMap);
    assertEquals(sourceActivity.byteMap, deserializedActivity.byteMap);
    assertEquals(sourceActivity.shortMap, deserializedActivity.shortMap);
    assertEquals(sourceActivity.intMap, deserializedActivity.intMap);
    assertEquals(sourceActivity.longMap, deserializedActivity.longMap);
    assertEquals(sourceActivity.charMap, deserializedActivity.charMap);
    assertEquals(sourceActivity.booleanMap, deserializedActivity.booleanMap);
    assertEquals(sourceActivity.stringMap, deserializedActivity.stringMap);
    assertEquals(sourceActivity.testDuration, deserializedActivity.testDuration);
    assertEquals(sourceActivity.testEnum, deserializedActivity.testEnum);
    assertEquals(sourceActivity.mappyBoi, deserializedActivity.mappyBoi);
    assertArrayEquals(sourceActivity.doublePrimIntArray, deserializedActivity.doublePrimIntArray);
    assertArrayEquals(sourceActivity.intListArrayArray, deserializedActivity.intListArrayArray);
    // TODO; Check equality for obnoxious (this is quite complex)
  }

  private Map<String, SerializedValue> createSerializedArguments() {
    final ParameterTestActivity testValues = new ParameterTestActivity();
    final Map<String, SerializedValue> arguments = new HashMap<>();

    // Primitive parameters
    arguments.put("primitiveDouble", SerializedValue.of(testValues.primitiveDouble));
    arguments.put("primitiveFloat", SerializedValue.of(testValues.primitiveFloat));
    arguments.put("primitiveByte", SerializedValue.of(testValues.primitiveByte));
    arguments.put("primitiveShort", SerializedValue.of(testValues.primitiveShort));
    arguments.put("primitiveInt", SerializedValue.of(testValues.primitiveInt));
    arguments.put("primitiveLong", SerializedValue.of(testValues.primitiveLong));
    arguments.put("primitiveChar", SerializedValue.of("" + testValues.primitiveChar));
    arguments.put("primitiveBoolean", SerializedValue.of(testValues.primitiveBoolean));

    // Boxed parameters
    arguments.put("boxedDouble", SerializedValue.of(testValues.boxedDouble));
    arguments.put("boxedFloat", SerializedValue.of(testValues.boxedFloat));
    arguments.put("boxedByte", SerializedValue.of(testValues.boxedByte));
    arguments.put("boxedShort", SerializedValue.of(testValues.boxedShort));
    arguments.put("boxedInt", SerializedValue.of(testValues.boxedInt));
    arguments.put("boxedLong", SerializedValue.of(testValues.boxedLong));
    arguments.put("boxedChar", SerializedValue.of("" + testValues.boxedChar));
    arguments.put("boxedBoolean", SerializedValue.of(testValues.boxedBoolean));
    arguments.put("string", SerializedValue.of(testValues.string));

    // Array parameters
    arguments.put("doubleArray", SerializedValue.of(
        List.of(
            SerializedValue.of(testValues.doubleArray[0]),
            SerializedValue.of(testValues.doubleArray[1])
        )
    ));
    arguments.put("floatArray", SerializedValue.of(
        List.of(
            SerializedValue.of(testValues.floatArray[0]),
            SerializedValue.of(testValues.floatArray[1])
        )
    ));
    arguments.put("byteArray", SerializedValue.of(
        List.of(
            SerializedValue.of(testValues.byteArray[0]),
            SerializedValue.of(testValues.byteArray[1])
        )
    ));
    arguments.put("shortArray", SerializedValue.of(
        List.of(
            SerializedValue.of(testValues.shortArray[0]),
            SerializedValue.of(testValues.shortArray[1])
        )
    ));
    arguments.put("intArray", SerializedValue.of(
        List.of(
            SerializedValue.of(testValues.intArray[0]),
            SerializedValue.of(testValues.intArray[1])
        )
    ));
    arguments.put("longArray", SerializedValue.of(
        List.of(
            SerializedValue.of(testValues.longArray[0]),
            SerializedValue.of(testValues.longArray[1])
        )
    ));
    arguments.put("charArray", SerializedValue.of(
        List.of(
            SerializedValue.of("" + testValues.charArray[0]),
            SerializedValue.of("" + testValues.charArray[1])
        )
    ));
    arguments.put("booleanArray", SerializedValue.of(
        List.of(
            SerializedValue.of(testValues.booleanArray[0]),
            SerializedValue.of(testValues.booleanArray[1])
        )
    ));
    arguments.put("stringArray", SerializedValue.of(
        List.of(
            SerializedValue.of(testValues.stringArray[0]),
            SerializedValue.of(testValues.stringArray[1])
        )
    ));

    // Primitive array parameters
    arguments.put("primDoubleArray", SerializedValue.of(
        List.of(
            SerializedValue.of(testValues.primDoubleArray[0]),
            SerializedValue.of(testValues.primDoubleArray[1])
        )
    ));
    arguments.put("primFloatArray", SerializedValue.of(
        List.of(
            SerializedValue.of(testValues.primFloatArray[0]),
            SerializedValue.of(testValues.primFloatArray[1])
        )
    ));
    arguments.put("primByteArray", SerializedValue.of(
        List.of(
            SerializedValue.of(testValues.primByteArray[0]),
            SerializedValue.of(testValues.primByteArray[1])
        )
    ));
    arguments.put("primShortArray", SerializedValue.of(
        List.of(
            SerializedValue.of(testValues.primShortArray[0]),
            SerializedValue.of(testValues.primShortArray[1])
        )
    ));
    arguments.put("primIntArray", SerializedValue.of(
        List.of(
            SerializedValue.of(testValues.primIntArray[0]),
            SerializedValue.of(testValues.primIntArray[1])
        )
    ));
    arguments.put("primLongArray", SerializedValue.of(
        List.of(
            SerializedValue.of(testValues.primLongArray[0]),
            SerializedValue.of(testValues.primLongArray[1])
        )
    ));
    arguments.put("primCharArray", SerializedValue.of(
        List.of(
            SerializedValue.of("" + testValues.primCharArray[0]),
            SerializedValue.of("" + testValues.primCharArray[1])
        )
    ));
    arguments.put("primBooleanArray", SerializedValue.of(
        List.of(
            SerializedValue.of(testValues.primBooleanArray[0]),
            SerializedValue.of(testValues.primBooleanArray[1])
        )
    ));

    // List parameters
    arguments.put("doubleList", SerializedValue.of(
        List.of(
            SerializedValue.of(testValues.doubleList.get(0)),
            SerializedValue.of(testValues.doubleList.get(1))
        )
    ));
    arguments.put("floatList", SerializedValue.of(
        List.of(
            SerializedValue.of(testValues.floatList.get(0)),
            SerializedValue.of(testValues.floatList.get(1))
        )
    ));
    arguments.put("byteList", SerializedValue.of(
        List.of(
            SerializedValue.of(testValues.byteList.get(0)),
            SerializedValue.of(testValues.byteList.get(1))
        )
    ));
    arguments.put("shortList", SerializedValue.of(
        List.of(
            SerializedValue.of(testValues.shortList.get(0)),
            SerializedValue.of(testValues.shortList.get(1))
        )
    ));
    arguments.put("intList", SerializedValue.of(
        List.of(
            SerializedValue.of(testValues.intList.get(0)),
            SerializedValue.of(testValues.intList.get(1))
        )
    ));
    arguments.put("longList", SerializedValue.of(
        List.of(
            SerializedValue.of(testValues.longList.get(0)),
            SerializedValue.of(testValues.longList.get(1))
        )
    ));
    arguments.put("charList", SerializedValue.of(
        List.of(
            SerializedValue.of("" + testValues.charList.get(0)),
            SerializedValue.of("" + testValues.charList.get(1))
        )
    ));
    arguments.put("booleanList", SerializedValue.of(
        List.of(
            SerializedValue.of(testValues.booleanList.get(0)),
            SerializedValue.of(testValues.booleanList.get(1))
        )
    ));
    arguments.put("stringList", SerializedValue.of(
        List.of(
            SerializedValue.of(testValues.stringList.get(0)),
            SerializedValue.of(testValues.stringList.get(1))
        )
    ));


    // Map Parameters
    final var doubleMapEntries = entryArray(testValues.doubleMap);
    arguments.put("doubleMap", SerializedValue.of(
        List.of(
            SerializedValue.of(
                Map.of(
                    "key", SerializedValue.of(doubleMapEntries[0].getKey()),
                    "value", SerializedValue.of(doubleMapEntries[0].getValue())
                )
            ),
            SerializedValue.of(
                Map.of(
                    "key", SerializedValue.of(doubleMapEntries[1].getKey()),
                    "value", SerializedValue.of(doubleMapEntries[1].getValue())
                )
            )
        )
    ));
    final var floatMapEntries = entryArray(testValues.floatMap);
    arguments.put("floatMap", SerializedValue.of(
        List.of(
            SerializedValue.of(
                Map.of(
                    "key", SerializedValue.of(floatMapEntries[0].getKey()),
                    "value", SerializedValue.of(floatMapEntries[0].getValue())
                )
            ),
            SerializedValue.of(
                Map.of(
                    "key", SerializedValue.of(floatMapEntries[1].getKey()),
                    "value", SerializedValue.of(floatMapEntries[1].getValue())
                )
            )
        )
    ));
    final var byteMapEntries = entryArray(testValues.byteMap);
    arguments.put("byteMap", SerializedValue.of(
        List.of(
            SerializedValue.of(
                Map.of(
                    "key", SerializedValue.of(byteMapEntries[0].getKey()),
                    "value", SerializedValue.of(byteMapEntries[0].getValue())
                )
            ),
            SerializedValue.of(
                Map.of(
                    "key", SerializedValue.of(byteMapEntries[1].getKey()),
                    "value", SerializedValue.of(byteMapEntries[1].getValue())
                )
            )
        )
    ));
    final var shortMapEntries = entryArray(testValues.shortMap);
    arguments.put("shortMap", SerializedValue.of(
        List.of(
            SerializedValue.of(
                Map.of(
                    "key", SerializedValue.of(shortMapEntries[0].getKey()),
                    "value", SerializedValue.of(shortMapEntries[0].getValue())
                )
            ),
            SerializedValue.of(
                Map.of(
                    "key", SerializedValue.of(shortMapEntries[1].getKey()),
                    "value", SerializedValue.of(shortMapEntries[1].getValue())
                )
            )
        )
    ));
    final var intMapEntries = entryArray(testValues.intMap);
    arguments.put("intMap", SerializedValue.of(
        List.of(
            SerializedValue.of(
                Map.of(
                    "key", SerializedValue.of(intMapEntries[0].getKey()),
                    "value", SerializedValue.of(intMapEntries[0].getValue())
                )
            ),
            SerializedValue.of(
                Map.of(
                    "key", SerializedValue.of(intMapEntries[1].getKey()),
                    "value", SerializedValue.of(intMapEntries[1].getValue())
                )
            )
        )
    ));
    final var longMapEntries = entryArray(testValues.longMap);
    arguments.put("longMap", SerializedValue.of(
        List.of(
            SerializedValue.of(
                Map.of(
                    "key", SerializedValue.of(longMapEntries[0].getKey()),
                    "value", SerializedValue.of(longMapEntries[0].getValue())
                )
            ),
            SerializedValue.of(
                Map.of(
                    "key", SerializedValue.of(longMapEntries[1].getKey()),
                    "value", SerializedValue.of(longMapEntries[1].getValue())
                )
            )
        )
    ));
    final var charMapEntries = entryArray(testValues.charMap);
    arguments.put("charMap", SerializedValue.of(
        List.of(
            SerializedValue.of(
                Map.of(
                    "key", SerializedValue.of("" + charMapEntries[0].getKey()),
                    "value", SerializedValue.of("" + charMapEntries[0].getValue())
                )
            ),
            SerializedValue.of(
                Map.of(
                    "key", SerializedValue.of("" + charMapEntries[1].getKey()),
                    "value", SerializedValue.of("" + charMapEntries[1].getValue())
                )
            )
        )
    ));
    final var booleanMapEntries = entryArray(testValues.booleanMap);
    arguments.put("booleanMap", SerializedValue.of(
        List.of(
            SerializedValue.of(
                Map.of(
                    "key", SerializedValue.of(booleanMapEntries[0].getKey()),
                    "value", SerializedValue.of(booleanMapEntries[0].getValue())
                )
            ),
            SerializedValue.of(
                Map.of(
                    "key", SerializedValue.of(booleanMapEntries[1].getKey()),
                    "value", SerializedValue.of(booleanMapEntries[1].getValue())
                )
            )
        )
    ));
    final var stringMapEntries = entryArray(testValues.stringMap);
    arguments.put("stringMap", SerializedValue.of(
        List.of(
            SerializedValue.of(
                Map.of(
                    "key", SerializedValue.of(stringMapEntries[0].getKey()),
                    "value", SerializedValue.of(stringMapEntries[0].getValue())
                )
            ),
            SerializedValue.of(
                Map.of(
                    "key", SerializedValue.of(stringMapEntries[1].getKey()),
                    "value", SerializedValue.of(stringMapEntries[1].getValue())
                )
            )
        )
    ));

    // Duration Parameter
    arguments.put("testDuration", new DurationValueMapper().serializeValue(testValues.testDuration));

    // Enum Parameter
    arguments.put("testEnum", SerializedValue.of(testValues.testEnum.name()));

    // Complex Parameters

    final var mappyBoiEntries = entryArray(testValues.mappyBoi);
    arguments.put("mappyBoi", SerializedValue.of(
        List.of(
            SerializedValue.of(
                Map.of(
                    "key", SerializedValue.of(mappyBoiEntries[0].getKey()),
                    "value", SerializedValue.of(
                        List.of(
                            SerializedValue.of(mappyBoiEntries[0].getValue().get(0)),
                            SerializedValue.of(mappyBoiEntries[0].getValue().get(1))
                        )
                    )
                )
            ),
            SerializedValue.of(
                Map.of(
                    "key", SerializedValue.of(mappyBoiEntries[1].getKey()),
                    "value", SerializedValue.of(
                        List.of(
                            SerializedValue.of(mappyBoiEntries[1].getValue().get(0)),
                            SerializedValue.of(mappyBoiEntries[1].getValue().get(1))
                        )
                    )
                )
            )
        )
    ));

    arguments.put("doublePrimIntArray", SerializedValue.of(
        List.of(
            SerializedValue.of(List.of(
                SerializedValue.of(testValues.doublePrimIntArray[0][0]),
                SerializedValue.of(testValues.doublePrimIntArray[0][1])
            )),
            SerializedValue.of(List.of(
                SerializedValue.of(testValues.doublePrimIntArray[1][0]),
                SerializedValue.of(testValues.doublePrimIntArray[1][1])
            ))
        )
    ));

    arguments.put("intListArrayArray", SerializedValue.of(
        List.of(
            SerializedValue.of(List.of(
                SerializedValue.of(List.of(
                    SerializedValue.of(testValues.intListArrayArray[0][0].get(0)),
                    SerializedValue.of(testValues.intListArrayArray[0][0].get(1))
                )),
                SerializedValue.of(List.of(
                    SerializedValue.of(testValues.intListArrayArray[0][1].get(0)),
                    SerializedValue.of(testValues.intListArrayArray[0][1].get(1))
                ))
            )),
            SerializedValue.of(List.of(
                SerializedValue.of(List.of(
                    SerializedValue.of(testValues.intListArrayArray[1][0].get(0)),
                    SerializedValue.of(testValues.intListArrayArray[1][0].get(1))
                )),
                SerializedValue.of(List.of(
                    SerializedValue.of(testValues.intListArrayArray[1][1].get(0)),
                    SerializedValue.of(testValues.intListArrayArray[1][1].get(1))
                ))
            ))
        )
    ));

    // Because obnixous is so obnoxious, we'll just hardcode it twice :(
    arguments.put("obnoxious", SerializedValue.of(
        List.of(
            SerializedValue.of(List.of(
                SerializedValue.of(Map.of(
                    "key", SerializedValue.of(List.of(
                        SerializedValue.of(List.of(
                            SerializedValue.of("300"),
                            SerializedValue.of("301")
                        )),
                        SerializedValue.of(List.of(
                            SerializedValue.of("302"),
                            SerializedValue.of("303")
                        ))
                    )),
                    "value", SerializedValue.of(List.of(
                        SerializedValue.of(Map.of(
                            "key", SerializedValue.of(500),
                            "value", SerializedValue.of(List.of(
                                SerializedValue.of(List.of(
                                    SerializedValue.of(400.0f),
                                    SerializedValue.of(401.0f)
                                )),
                                SerializedValue.of(List.of(
                                    SerializedValue.of(402.0f),
                                    SerializedValue.of(403.0f)
                                ))
                            ))
                        )),
                        SerializedValue.of(Map.of(
                            "key", SerializedValue.of(501),
                            "value", SerializedValue.of(List.of(
                                SerializedValue.of(List.of(
                                    SerializedValue.of(404.0f),
                                    SerializedValue.of(405.0f)
                                )),
                                SerializedValue.of(List.of(
                                    SerializedValue.of(406.0f),
                                    SerializedValue.of(407.0f)
                                ))
                            ))
                        ))
                    ))
                )),
                SerializedValue.of(Map.of(
                    "key", SerializedValue.of(List.of(
                        SerializedValue.of(List.of(
                            SerializedValue.of("304"),
                            SerializedValue.of("305")
                        )),
                        SerializedValue.of(List.of(
                            SerializedValue.of("306"),
                            SerializedValue.of("307")
                        ))
                    )),
                    "value", SerializedValue.of(List.of(
                        SerializedValue.of(Map.of(
                            "key", SerializedValue.of(502),
                            "value", SerializedValue.of(List.of(
                                SerializedValue.of(List.of(
                                    SerializedValue.of(408.0f),
                                    SerializedValue.of(409.0f)
                                )),
                                SerializedValue.of(List.of(
                                    SerializedValue.of(410.0f),
                                    SerializedValue.of(411.0f)
                                ))
                            ))
                        )),
                        SerializedValue.of(Map.of(
                            "key", SerializedValue.of(503),
                            "value", SerializedValue.of(List.of(
                                SerializedValue.of(List.of(
                                    SerializedValue.of(412.0f),
                                    SerializedValue.of(413.0f)
                                )),
                                SerializedValue.of(List.of(
                                    SerializedValue.of(414.0f),
                                    SerializedValue.of(415.0f)
                                ))
                            ))
                        ))
                    ))
                )),
                SerializedValue.of(Map.of(
                    "key", SerializedValue.of(List.of(
                        SerializedValue.of(List.of(
                            SerializedValue.of("308"),
                            SerializedValue.of("309")
                        )),
                        SerializedValue.of(List.of(
                            SerializedValue.of("310"),
                            SerializedValue.of("311")
                        ))
                    )),
                    "value", SerializedValue.of(List.of(
                        SerializedValue.of(Map.of(
                            "key", SerializedValue.of(504),
                            "value", SerializedValue.of(List.of(
                                SerializedValue.of(List.of(
                                    SerializedValue.of(416.0f),
                                    SerializedValue.of(417.0f)
                                )),
                                SerializedValue.of(List.of(
                                    SerializedValue.of(418.0f),
                                    SerializedValue.of(419.0f)
                                ))
                            ))
                        )),
                        SerializedValue.of(Map.of(
                            "key", SerializedValue.of(505),
                            "value", SerializedValue.of(List.of(
                                SerializedValue.of(List.of(
                                    SerializedValue.of(420.0f),
                                    SerializedValue.of(421.0f)
                                )),
                                SerializedValue.of(List.of(
                                    SerializedValue.of(422.0f),
                                    SerializedValue.of(423.0f)
                                ))
                            ))
                        ))
                    ))
                )),
                SerializedValue.of(Map.of(
                    "key", SerializedValue.of(List.of(
                        SerializedValue.of(List.of(
                            SerializedValue.of("312"),
                            SerializedValue.of("313")
                        )),
                        SerializedValue.of(List.of(
                            SerializedValue.of("314"),
                            SerializedValue.of("315")
                        ))
                    )),
                    "value", SerializedValue.of(List.of(
                        SerializedValue.of(Map.of(
                            "key", SerializedValue.of(506),
                            "value", SerializedValue.of(List.of(
                                SerializedValue.of(List.of(
                                    SerializedValue.of(424.0f),
                                    SerializedValue.of(425.0f)
                                )),
                                SerializedValue.of(List.of(
                                    SerializedValue.of(426.0f),
                                    SerializedValue.of(427.0f)
                                ))
                            ))
                        )),
                        SerializedValue.of(Map.of(
                            "key", SerializedValue.of(507),
                            "value", SerializedValue.of(List.of(
                                SerializedValue.of(List.of(
                                    SerializedValue.of(428.0f),
                                    SerializedValue.of(429.0f)
                                )),
                                SerializedValue.of(List.of(
                                    SerializedValue.of(430.0f),
                                    SerializedValue.of(431.0f)
                                ))
                            ))
                        ))
                    ))
                ))
            ))
        ))
    );

    return arguments;
  }

  @SuppressWarnings("unchecked")
  private static <K, V> Map.Entry<K, V>[] entryArray(final Map<K, V> map) {
    return map.entrySet().toArray(new Map.Entry[0]);
  }
}
