package gov.nasa.jpl.aerie.scheduler;

import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static gov.nasa.jpl.aerie.scheduler.constraints.activities.ActivityExpression.subsetOrEqual;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SerializedValueSubsetTest {

  @Test
  public void testSubsetEqualityMap(){
    final var reference = SerializedValue.of(
        Map.of("A", SerializedValue.of(8.0),
               "B", SerializedValue.of(
                Map.of("C", SerializedValue.of(14), "D", SerializedValue.of(15))
            )));

    final var subsetMap = SerializedValue.of(
        Map.of(
            "B", SerializedValue.of(
                Map.of("D", SerializedValue.of(15))
            )));
    final var subsetMapWithSerializedNull = SerializedValue.of(
        Map.of(
            "B", SerializedValue.of(
                Map.of("D", SerializedValue.NULL)
            )));
    final var notSubsetMap = SerializedValue.of(
        Map.of(
            "B", SerializedValue.of(
                Map.of("D", SerializedValue.of(7))
            )));

    assertTrue(subsetOrEqual(reference, subsetMap));
    assertFalse(subsetOrEqual(reference, notSubsetMap));
    assertTrue(subsetOrEqual(reference, subsetMapWithSerializedNull));

  }

  @Test
  public void testSubsetEqualityList(){
    final var reference = SerializedValue.of(List.of(SerializedValue.of(8.0), SerializedValue.of(6.0), SerializedValue.of(9.0)));
    final var subsetList = SerializedValue.of(List.of(SerializedValue.of(8.0),SerializedValue.of(6.0)));
    final var nullList = SerializedValue.of(List.of(SerializedValue.NULL));
    final var notSubsetList = SerializedValue.of(List.of(SerializedValue.of(8.0),SerializedValue.of(7.0)));
    final var wrongOrderList = SerializedValue.of(List.of(SerializedValue.of(9.0), SerializedValue.of(6.0), SerializedValue.of(8.0)));
    assertTrue(subsetOrEqual(reference, subsetList));
    assertTrue(subsetOrEqual(reference, nullList));
    assertFalse(subsetOrEqual(reference, notSubsetList));
    assertFalse(subsetOrEqual(reference, wrongOrderList));
  }
  @Test
  public void testSubsetEqualityValuesInteger(){
    final var reference = SerializedValue.of(1111);
    final var sameValue = SerializedValue.of(1111);
    final var differentValue = SerializedValue.of(1112);
    assertTrue(subsetOrEqual(reference, sameValue));
    assertFalse(subsetOrEqual(reference, differentValue));
  }

  @Test
  public void testSubsetEqualityValuesBoolean(){
    final var reference = SerializedValue.of(true);
    final var sameValue = SerializedValue.of(true);
    final var differentValue = SerializedValue.of(false);
    final var differentType = SerializedValue.of(11);
    assertTrue(subsetOrEqual(reference, sameValue));
    assertFalse(subsetOrEqual(reference, differentValue));
    assertFalse(subsetOrEqual(reference, differentType));
  }

  @Test
  public void testSubsetEqualityValuesDecimal(){
    final var reference = SerializedValue.of(BigDecimal.valueOf(122.56));
    final var sameValue = SerializedValue.of(BigDecimal.valueOf(122.56));
    final var differentValue = SerializedValue.of(BigDecimal.valueOf(122.57));
    final var differentType = SerializedValue.of(11);
    assertTrue(subsetOrEqual(reference, sameValue));
    assertFalse(subsetOrEqual(reference, differentValue));
    assertFalse(subsetOrEqual(reference, differentType));
  }

  @Test
  public void testSubsetEqualityValuesString(){
    final var reference = SerializedValue.of("AbCd");
    final var sameValue = SerializedValue.of("AbCd");
    final var differentValue = SerializedValue.of("bCd");
    final var differentType = SerializedValue.of(11);
    final var differentCase = SerializedValue.of("abcd");
    assertTrue(subsetOrEqual(reference, sameValue));
    assertFalse(subsetOrEqual(reference, differentValue));
    assertFalse(subsetOrEqual(reference, differentCase));
    assertFalse(subsetOrEqual(reference, differentType));
  }
}
