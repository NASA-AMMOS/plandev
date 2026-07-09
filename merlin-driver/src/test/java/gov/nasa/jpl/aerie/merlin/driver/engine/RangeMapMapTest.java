package gov.nasa.jpl.aerie.merlin.driver.engine;

import com.google.common.collect.Range;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RangeMapMapTest {

  private RangeMapMap<Double, String, Integer> map;
  private RangeMapMap<Integer, String, Integer> imap;
  private final Map<String, Integer> mA1 = Map.of("A", 1);
  private final Map<String, Integer> mA0 = Map.of("A", 0);
  private final Map<String, Integer> mB2 = Map.of("B", 2);
  private final Map<String, Integer> mA1B2 = Map.of("A", 1, "B", 2);
  private final Map<String, Integer> mA0B2 = Map.of("A", 0, "B", 2);

  @BeforeEach
  void setUp() {
    map = new RangeMapMap<>();
    imap = new RangeMapMap<>();
  }

  @Test
  void add1() {
    map.add(Range.closed(1.0, 3.0), "A", 1);
    map.addAll(Range.closed(1.0, 5.0), mA1B2);
    System.out.println(map);
    assert(map.asMapOfRanges().size() == 1);
  }

  @Test
  void add2() {
    map.addAll(Range.closed(1.0, 5.0), mA1B2);
    map.add(Range.closed(1.0, 3.0), "A", 1);
    System.out.println(map);
    assert(map.asMapOfRanges().size() == 1);
  }

  @Test
  void add3() {
    map.addAll(Range.closed(1.0, 5.0), mA1B2);
    map.add(Range.closed(1.0, 3.0), "A", 0);
    map.addAll(Range.closed(2.0, 2.0), mA0B2);
    System.out.println(map);
    assert(map.asMapOfRanges().size() == 2);
    assert(map.asMapOfRanges().values().contains(mA1B2));
    assert(map.asMapOfRanges().values().contains(mA0B2));  // this could fail if contains() isn't like RangeMapMap.equals(Map, Map)
  }

  @Test
  void remove() {
    map.addAll(Range.closed(1.0, 5.0), mA1B2);
    map.remove(Range.closed(1.0, 3.0), "A", 1);
    System.out.println(map);
    assert(map.asMapOfRanges().size() == 2);
    assert(map.asMapOfRanges().values().contains(mA1B2));
    assert(map.asMapOfRanges().values().contains(mB2));  // this could fail if contains() isn't like RangeMapMap.equals(Map, Map)
  }

  @Test
  void removeAll() {
    map.addAll(Range.closed(1.0, 5.0), mA1B2);
    map.removeAll(Range.closed(0.0, 5.0), mA1B2);
    System.out.println(map);
    assert(map.asMapOfRanges().size() == 0);
  }

  @Test
  void removeAll2() {
    map.addAll(Range.closed(1.0, 5.0), mA1B2);
    map.remove(Range.closed(0.0, 5.0), "A", 1);
    map.remove(Range.closed(1.0, 6.0), "B", 2);
    System.out.println(map);
    assert(map.asMapOfRanges().size() == 0);
  }


}
