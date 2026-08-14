package gov.nasa.jpl.aerie.banananation.activities;

import gov.nasa.jpl.aerie.merlin.framework.annotations.AutoValueMapper;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.util.List;
import java.util.Map;

@AutoValueMapper.Record
public record RecordParameter<T>(
    // Primitive parameters
    double primitiveDouble,
    float primitiveFloat,
    byte primitiveByte,
    short primitiveShort,
    int primitiveInt,
    long primitiveLong,
    char primitiveChar,
    boolean primitiveBoolean,

    // Boxed parameters
    Double boxedDouble,
    Float boxedFloat,
    Byte boxedByte,
    Short boxedShort,
    Integer boxedInt,
    Long boxedLong,
    Character boxedChar,
    Boolean boxedBoolean,
    String string,

    // Array parameters
    Double[] doubleArray,
    Float[] floatArray,
    Byte[] byteArray,
    Short[] shortArray,
    Integer[] intArray,
    Long[] longArray,
    Character[] charArray,
    Boolean[] booleanArray,
    String[] stringArray,

    // Primitive Array parameters
    double[] primDoubleArray,
    float[] primFloatArray,
    byte[] primByteArray,
    short[] primShortArray,
    int[] primIntArray,
    long[] primLongArray,
    char[] primCharArray,
    boolean[] primBooleanArray,

    // List parameters
    List<Double> doubleList,
    List<Float> floatList,
    List<Byte> byteList,
    List<Short> shortList,
    List<Integer> intList,
    List<Long> longList,
    List<Character> charList,
    List<Boolean> booleanList,
    List<String> stringList,

    // Map Parameters
    Map<Double, Double> doubleMap,
    Map<Float, Float> floatMap,
    Map<Byte, Byte> byteMap,
    Map<Short, Short> shortMap,
    Map<Integer, Integer> intMap,
    Map<Long, Long> longMap,
    Map<Character, Character> charMap,
    Map<Boolean, Boolean> booleanMap,
    Map<String, String> stringMap,

    // Duration type
    Duration testDuration,

    // Enum type
    Tenum testEnum,

    // Complex Parameters
    Map<Integer, List<String>> mappyBoi,

    int[][] doublePrimIntArray,

    List<Integer>[][] intListArrayArray,

    List<Map<String[][], Map<Integer, List<Float>[]>>> obnoxious,

    Nested nested,

    T genericParameter
)
{
  public enum Tenum { A, B, C }

  @AutoValueMapper.Record
  public record Nested(String a, Map<Integer, Character> b) {
  }
}
