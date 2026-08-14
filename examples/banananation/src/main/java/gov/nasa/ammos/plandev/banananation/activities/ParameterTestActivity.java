package gov.nasa.jpl.aerie.banananation.activities;

import gov.nasa.jpl.aerie.contrib.metadata.Unit;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export.Parameter;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.util.List;
import java.util.Map;

/**
 * An activity type to test the mapper generation capability of the annotation processor.
 * Most, if not all, of the primitive types supported by Java are exercised here,
 * as well as several compound parameter types built up from those primitives.
 */
@ActivityType("ParameterTest")
public final class ParameterTestActivity {
  // Primitive parameters
  @Parameter public double primitiveDouble = 3.141;
  @Parameter public float primitiveFloat = 1.618f;
  @Parameter public byte primitiveByte = 16;
  @Parameter public short primitiveShort = 32;
  @Parameter public int primitiveInt = 64;
  @Parameter public long primitiveLong = 128;
  @Parameter public char primitiveChar = 'g';
  @Parameter public boolean primitiveBoolean = true;

  // Boxed parameters
  @Parameter public Double boxedDouble;
  @Parameter public Float boxedFloat;
  @Parameter public Byte boxedByte;
  @Parameter public Short boxedShort;
  @Parameter public Integer boxedInt;
  @Parameter public Long boxedLong;
  @Parameter public Character boxedChar;
  @Parameter public Boolean boxedBoolean;
  @Parameter public String string;

  // Array parameters
  @Parameter public Double[] doubleArray;
  @Parameter public Float[] floatArray;
  @Parameter public Byte[] byteArray;
  @Parameter public Short[] shortArray;
  @Parameter public Integer[] intArray;
  @Parameter public Long[] longArray;
  @Parameter public Character[] charArray;
  @Parameter public Boolean[] booleanArray;
  @Parameter public String[] stringArray;

  // Primitive Array parameters
  @Parameter public double[] primDoubleArray;
  @Parameter public float[] primFloatArray;
  @Parameter public byte[] primByteArray;
  @Parameter public short[] primShortArray;
  @Parameter public int[] primIntArray;
  @Parameter public long[] primLongArray;
  @Parameter public char[] primCharArray;
  @Parameter public boolean[] primBooleanArray;

  // List parameters
  @Parameter public List<@Unit("m") Double> doubleList;
  @Parameter public List<Float> floatList;
  @Parameter public List<Byte> byteList;
  @Parameter public List<Short> shortList;
  @Parameter public List<Integer> intList;
  @Parameter public List<Long> longList;
  @Parameter public List<Character> charList;
  @Parameter public List<Boolean> booleanList;
  @Parameter public List<String> stringList;

  // Map Parameters
  @Parameter public Map<@Unit("s") Double, @Unit("W") Double> doubleMap;
  @Parameter public Map<Float, Float> floatMap;
  @Parameter public Map<Byte, Byte> byteMap;
  @Parameter public Map<Short, Short> shortMap;
  @Parameter public Map<Integer, Integer> intMap;
  @Parameter public Map<Long, Long> longMap;
  @Parameter public Map<Character, Character> charMap;
  @Parameter public Map<Boolean, Boolean> booleanMap;
  @Parameter public Map<String, String> stringMap;

  // Duration type
  @Parameter public Duration testDuration;

  // Enum type
  public enum Tenum { A, B, C }
  @Parameter public Tenum testEnum;

  // Complex Parameters
  @Parameter
  public Map<Integer, List<String>> mappyBoi;

  @Parameter
  public int[][] doublePrimIntArray;

  @Parameter
  public List<Integer>[][] intListArrayArray;

  @Parameter
  public List<Map<String[][], Map<Integer, List<Float>[]>>> obnoxious;

  @Parameter
  public RecordParameter<List<String>> record;

  public ParameterTestActivity() {
    this.boxedDouble = 6.282;
    this.boxedFloat = 3.236f;
    this.boxedByte = 116;
    this.boxedShort = 132;
    this.boxedInt = 164;
    this.boxedLong = 1128L;
    this.boxedChar = 'G';
    this.boxedBoolean =  false;
    this.string = "h";
    this.doubleArray = List.of(1.0, 2.0).toArray(new Double[2]);
    this.floatArray = List.of(3.0f, 4.0f).toArray(new Float[2]);
    this.byteArray = List.of((byte)5, (byte)6).toArray(new Byte[2]);
    this.shortArray = List.of((short)7, (short)8).toArray(new Short[2]);
    this.intArray = List.of(9, 10).toArray(new Integer[2]);
    this.longArray = List.of((long)11, (long)12).toArray(new Long[2]);
    this.charArray = List.of('a', 'b').toArray(new Character[2]);
    this.booleanArray = List.of(true, false).toArray(new Boolean[2]);
    this.stringArray = List.of("17", "18").toArray(new String[2]);
    this.primDoubleArray = new double[] {102.0, 103.0};
    this.primFloatArray = new float[] {(float)104.0, (float)105.0};
    this.primByteArray = new byte[] {(byte)106, (byte)107};
    this.primShortArray = new short[] {(short)108, (short)109};
    this.primIntArray = new int[] {110, 111};
    this.primLongArray = new long[] {(long)112, (long)113};
    this.primCharArray = new char[] {'c', 'd'};
    this.primBooleanArray = new boolean[] {true, false};
    this.doubleList = List.of(19.0, 20.0);
    this.floatList = List.of(21.0f, 22.0f);
    this.byteList = List.of((byte)23, (byte)24);
    this.shortList = List.of((short)25, (short)26);
    this.intList = List.of(27, 28);
    this.longList = List.of((long)29, (long)30);
    this.charList = List.of('c', 'd');
    this.booleanList = List.of(false, true);
    this.stringList = List.of("35", "36");
    this.doubleMap = Map.of(37.0, 38.0, 39.0, 40.0);
    this.floatMap = Map.of(41.0f, 42.0f, 43.0f, 44.0f);
    this.byteMap = Map.of((byte)45, (byte)46, (byte)47, (byte)48);
    this.shortMap = Map.of((short)49, (short)50, (short)51, (short)52);
    this.intMap = Map.of(53, 54, 55, 56);
    this.longMap = Map.of((long)57, (long)58, (long)59, (long)60);
    this.charMap = Map.of('e', 'f', 'g', 'h');
    this.booleanMap = Map.of(false, true, true, false);
    this.stringMap = Map.of("69", "70", "71", "72");
    this.testDuration = Duration.of(300000000, Duration.MICROSECONDS);
    this.testEnum = Tenum.A;
    this.mappyBoi = Map.of(
            100, List.of("abc", "xyz"),
            200, List.of("def", "uvw")
    );
    this.doublePrimIntArray = new int[][] {
            new int[] {101, 102},
            new int[] {103, 103}
    };
    @SuppressWarnings("unchecked")
    final List<Integer>[][] intListArrayArray = new List[][] {
            new List[] {
                    List.of(200, 201),
                    List.of(202, 203),
            },
            new List[] {
                    List.of(204, 205),
                    List.of(206, 207)
            }
    };
    this.intListArrayArray = intListArrayArray;
    @SuppressWarnings("unchecked")
    final List<Map<String[][], Map<Integer, List<Float>[]>>> obnoxious = List.of(
            Map.of(
                    new String[][] {
                            new String[] {"300", "301"},
                            new String[] {"302", "303"}
                    }, Map.of(
                            500, new List[] {
                                    List.of(400.0f, 401.0f),
                                    List.of(402.0f, 403.0f)
                            },
                            501, new List[] {
                                    List.of(404.0f, 405.0f),
                                    List.of(406.0f, 407.0f)
                            }
                    ),
                    new String[][] {
                            new String[] {"304", "305"},
                            new String[] {"306", "307"}
                    }, Map.of(
                            502, new List[] {
                                    List.of(408.0f, 409.0f),
                                    List.of(410.0f, 411.0f)
                            },
                            503, new List[] {
                                    List.of(412.0f, 413.0f),
                                    List.of(414.0f, 415.0f)
                            }
                    )
            ),
            Map.of(
                    new String[][] {
                            new String[] {"308", "309"},
                            new String[] {"310", "311"}
                    }, Map.of(
                            504, new List[] {
                                    List.of(416.0f, 417.0f),
                                    List.of(418.0f, 419.0f)
                            },
                            505, new List[] {
                                    List.of(420.0f, 421.0f),
                                    List.of(422.0f, 423.0f)
                            }
                    ),
                    new String[][] {
                            new String[] {"312", "313"},
                            new String[] {"314", "315"}
                    }, Map.of(
                            506, new List[] {
                                    List.of(424.0f, 425.0f),
                                    List.of(426.0f, 427.0f)
                            },
                            507, new List[] {
                                    List.of(428.0f, 429.0f),
                                    List.of(430.0f, 431.0f)
                            }
                    )
            )
    );
    this.obnoxious = obnoxious;

    this.record = new RecordParameter<>(
        this.primitiveDouble,
        this.primitiveFloat,
        this.primitiveByte,
        this.primitiveShort,
        this.primitiveInt,
        this.primitiveLong,
        this.primitiveChar,
        this.primitiveBoolean,
        this.boxedDouble,
        this.boxedFloat,
        this.boxedByte,
        this.boxedShort,
        this.boxedInt,
        this.boxedLong,
        this.boxedChar,
        this.boxedBoolean,
        this.string,
        this.doubleArray,
        this.floatArray,
        this.byteArray,
        this.shortArray,
        this.intArray,
        this.longArray,
        this.charArray,
        this.booleanArray,
        this.stringArray,
        this.primDoubleArray,
        this.primFloatArray,
        this.primByteArray,
        this.primShortArray,
        this.primIntArray,
        this.primLongArray,
        this.primCharArray,
        this.primBooleanArray,
        this.doubleList,
        this.floatList,
        this.byteList,
        this.shortList,
        this.intList,
        this.longList,
        this.charList,
        this.booleanList,
        this.stringList,
        this.doubleMap,
        this.floatMap,
        this.byteMap,
        this.shortMap,
        this.intMap,
        this.longMap,
        this.charMap,
        this.booleanMap,
        this.stringMap,
        this.testDuration,
        RecordParameter.Tenum.valueOf(this.testEnum.name()),
        this.mappyBoi,
        this.doublePrimIntArray,
        this.intListArrayArray,
        this.obnoxious,
        new RecordParameter.Nested("", Map.of()),
        List.of("")
    );
  }
}
