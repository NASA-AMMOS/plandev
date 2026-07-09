package gov.nasa.jpl.aerie.merlin.driver.engine;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import org.junit.jupiter.api.Test;
import com.google.common.collect.Range;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;

import java.util.Set;

class RangeSetMapTest {
  private RangeSetMap<Double, String> map;
  private RangeSetMap<Integer, String> imap;

  @BeforeEach
  void setUp() {
    map = new RangeSetMap<>();
    imap = new RangeSetMap<>();
  }

  @Test
  void testAddSingleRange() {
    map.add(Range.closed(1.0, 5.0), "A");
    assertEquals("{[1.0..5.0]=[A]}", map.toString());
  }

  @Test
  void testAddOverlappingRanges() {
    map.add(Range.closed(1.0, 5.0), "A");
    map.add(Range.closed(3.0, 7.0), "B");
    assertEquals("{[1.0..3.0)=[A], [3.0..5.0]=[A, B], (5.0..7.0]=[B]}", map.toString());
  }

  @Test
  void testAddContainedRange() {
    map.add(Range.closed(1.0, 10.0), "A");
    map.add(Range.closed(3.0, 7.0), "B");
    assertEquals("{[1.0..3.0)=[A], [3.0..7.0]=[A, B], (7.0..10.0]=[A]}", map.toString());
  }

  @Test
  void testAddExtendingRange() {
    map.add(Range.closed(1.0, 5.0), "A");
    map.add(Range.closed(-2.0, 7.0), "B");
    assertEquals("{[-2.0..1.0)=[B], [1.0..5.0]=[A, B], (5.0..7.0]=[B]}", map.toString());
  }

  @Test
  void testRemoveValue() {
    map.add(Range.closed(1.0, 5.0), "A");
    map.add(Range.closed(3.0, 7.0), "B");
    map.remove(Range.closed(2.0, 6.0), "A");
    assertEquals("{[1.0..2.0)=[A], [3.0..7.0]=[B]}", map.toString());
  }

  @Test
  void testGetValue() {
    map.add(Range.closed(1.0, 5.0), "A");
    map.add(Range.closed(3.0, 7.0), "B");
    System.out.println(map);
    assertEquals(Set.of("A"), map.get(2.0));
    assertEquals(Set.of("A", "B"), map.get(4.0));
    assertEquals(Set.of("B"), map.get(6.0));
    assertTrue(map.get(0.0).isEmpty());
    assertTrue(map.get(8.0).isEmpty());
  }

  @Test
  void testComplexOverlappingScenario() {
    map.add(Range.closed(1.0, 10.0), "A");
    map.add(Range.closed(5.0, 15.0), "B");
    map.add(Range.closed(0.0, 7.0), "C");
    assertEquals("{[0.0..1.0)=[C], [1.0..5.0)=[A, C], [5.0..7.0]=[A, B, C], (7.0..10.0]=[A, B], (10.0..15.0]=[B]}", map.toString());
  }
//}

  //private RangeSetMap<Integer, String> map;

//  @BeforeEach
//  void setUp() {
//    map = new RangeSetMap<>();
//  }

  @Test
  void testAddSingleRangeI() {
    imap.add(Range.closed(1, 5), "A");
    assertEquals("{[1..5]=[A]}", imap.toString());
  }

  @Test
  void testAddOverlappingRangesI() {
    imap.add(Range.closed(1, 5), "A");
    imap.add(Range.closed(3, 7), "B");
    //("{[1..2]=[A], [3..5]=[A, B], [6..7]=[B]}", imap.toString());
    assertEquals("{[1..3)=[A], [3..5]=[A, B], (5..7]=[B]}", imap.toString());
  }

  @Test
  void testAddContainedRangeI() {
    imap.add(Range.closed(1, 10), "A");
    imap.add(Range.closed(3, 7), "B");
    assertEquals("{[1..3)=[A], [3..7]=[A, B], (7..10]=[A]}", imap.toString());
  }

  @Test
  void testAddExtendingRangeI() {
    imap.add(Range.closed(1, 5), "A");
    imap.add(Range.closed(-2, 7), "B");
    assertEquals("{[-2..1)=[B], [1..5]=[A, B], (5..7]=[B]}", imap.toString());
  }

  @Test
  void testRemoveAllValuesInRangeI() {
    imap.add(Range.closed(1, 5), "A");
    imap.add(Range.closed(3, 7), "B");
    imap.remove(Range.closed(3, 5), "A");
    imap.remove(Range.closed(3, 5), "B");
    assertEquals("{[1..3)=[A], (5..7]=[B]}", imap.toString());
  }

  @Test
  void testAddMultipleValuesToSameRangeI() {
    imap.add(Range.closed(1, 5), "A");
    imap.add(Range.closed(1, 5), "B");
    imap.add(Range.closed(1, 5), "C");
    assertEquals("{[1..5]=[A, B, C]}", imap.toString());
  }

  @Test
  void add() {
    var x = new RangeSetMap<Integer, Integer>();
    x.add(Range.closed(0, 100), 5);
    x.add(Range.closed(-100, 3), 7);
    System.out.println(x);
    assertEquals(x.get(-1).size(), 1);
    assertEquals(x.get(0).size(), 2);
    assertEquals(x.get(2).size(), 2);
    assertEquals(x.get(3).size(), 2);
    assertEquals(x.get(100).size(), 1);
  }
  @Test
  void addDurationMap() {
    var x = new RangeSetMap<Duration, Integer>();
    x.add(Range.closed(Duration.ZERO, Duration.MAX_VALUE), 5);
    x.add(Range.closed(Duration.MIN_VALUE, Duration.of(3, Duration.SECONDS)), 7);
    System.out.println(x);
    assertEquals(x.get(Duration.of(-1, Duration.SECONDS)).size(), 1);
    assertEquals(x.get(Duration.ZERO).size(), 2);
    assertEquals(x.get(Duration.of(2, Duration.SECONDS)).size(), 2);
    assertEquals(x.get(Duration.of(3, Duration.SECONDS)).size(), 2);
    assertEquals(x.get(Duration.MAX_VALUE).size(), 1);
  }

  @Test
  void testComplexOverlappingScenarioI() {
    RangeSetMap<Integer, String> imap = new RangeSetMap<>();
    imap.add(Range.closed(1, 10), "A");
    imap.add(Range.closed(5, 15), "B");
    imap.add(Range.closed(0, 7), "C");
    assertEquals("{[0..1)=[C], [1..5)=[A, C], [5..7]=[A, B, C], (7..10]=[A, B], (10..15]=[B]}", imap.toString());
  }

  @Test
  void testGetValueI() {
    RangeSetMap<Integer, String> imap = new RangeSetMap<>();
    imap.add(Range.closed(1, 5), "A");
    imap.add(Range.closed(3, 7), "B");
    assertEquals(Set.of("A"), imap.get(2));
    assertEquals(Set.of("A", "B"), imap.get(4));
    assertEquals(Set.of("B"), imap.get(6));
    assertEquals(Set.of(), imap.get(0));
    assertEquals(Set.of(), imap.get(8));
  }

  @Test
  void testRemoveValueI() {
    RangeSetMap<Integer, String> imap = new RangeSetMap<>();
    imap.add(Range.closed(1, 5), "A");
    imap.add(Range.closed(3, 7), "B");
    imap.remove(Range.closed(2, 6), "A");
    assertEquals("{[1..2)=[A], [3..7]=[B]}", imap.toString());
  }

  @Test
  void testRemoveValueAtPointI() {
    RangeSetMap<Integer, String> imap = new RangeSetMap<>();
    imap.add(Range.closed(1, 5), "A");
    imap.remove(Range.closed(2, 2), "A");
    assertEquals("{[1..2)=[A], (2..5]=[A]}", imap.toString());
  }


  @Test
  void testRemoveValuesI() {
    RangeSetMap<Integer, String> imap = new RangeSetMap<>();
    imap.add(Range.closed(1, 5), "A");
    imap.remove(Range.closed(2, 2), "A");
    imap.add(Range.closed(3, 7), "B");
    imap.add(Range.closed(4, 9), "C");
    imap.remove(Range.closed(3, 4), "B");
    imap.remove(Range.closed(7, 8), "C");
    imap.add(Range.closed(-3, -1), "D");
    imap.remove(Range.closed(1, 3), "D");
    assertEquals("{[-3..-1]=[D], [1..2)=[A], (2..4)=[A], [4..4]=[A, C], (4..5]=[A, B, C], (5..7)=[B, C], [7..7]=[B], (8..9]=[C]}", imap.toString());
  }


}

