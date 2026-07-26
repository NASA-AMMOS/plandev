package gov.nasa.jpl.aerie.merlin.server.services;

import gov.nasa.jpl.aerie.merlin.protocol.model.InputType.Parameter;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import gov.nasa.jpl.aerie.merlin.server.models.ActivityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate's whole job is to catch results that a JAR model could not have produced but an external
 * backend can: a resource nobody registered, a schema that drifted since the model was introspected, a
 * span pointing at a directive we never sent. Each test below is one of those.
 */
public final class ExternalResultsGateTest {
  private static final long SIM_DURATION_US = 86_400_000_000L;

  private static final ActivityType COLLECT = new ActivityType(
      "CollectScience",
      List.of(new Parameter("d", ValueSchema.DURATION), new Parameter("label", ValueSchema.STRING)),
      List.of("d"),
      ValueSchema.ofStruct(Map.of()),
      Optional.empty(),
      Optional.empty());

  private static final Map<String, ValueSchema> RESOURCES = Map.of(
      "Power", ValueSchema.REAL,
      "Mode", ValueSchema.ofVariant(List.of(new ValueSchema.Variant("Off", "Off"), new ValueSchema.Variant("On", "On"))));

  private static ExternalResultsGate gate() {
    return gate(ExternalResultsGate.Mode.WARN);
  }

  private static ExternalResultsGate gate(final ExternalResultsGate.Mode mode) {
    return ExternalResultsGate.withMode(mode, Map.of("CollectScience", COLLECT), RESOURCES, Set.of(1L, 2L), SIM_DURATION_US);
  }

  /** A well-formed result set must produce nothing, including under REJECT -- otherwise the gate is noise. */
  @Test
  void acceptsResultsThatMatchTheRegisteredModel() {
    final var gate = gate(ExternalResultsGate.Mode.REJECT);
    gate.checkResourceProfile("Power", ValueSchema.REAL);
    gate.checkRealSegment("Power", 3_600_000_000L, 12.5, 0.25);
    gate.checkResourceProfile("Mode", RESOURCES.get("Mode"));
    gate.checkDiscreteSegment("Mode", 3_600_000_000L, SerializedValue.of("On"));
    gate.checkSpan(10, "CollectScience", 0, 60_000_000L,
                   Map.of("d", SerializedValue.of(60_000_000L), "label", SerializedValue.of("pass 1")), null, 1L);
    gate.checkSpan(11, "CollectScience", 60_000_000L, 60_000_000L,
                   Map.of("d", SerializedValue.of(60_000_000L)), 10L, null);
    assertDoesNotThrow(gate::finish);
    assertEquals(List.of(), gate.findings());
  }

  @Nested
  final class Profiles {
    @Test
    void flagsAResourceTheModelNeverRegistered() {
      final var gate = gate();
      gate.checkResourceProfile("Ghost", ValueSchema.REAL);
      assertTrue(only(gate).contains("'Ghost' is not a registered resource type"), only(gate));
    }

    /**
     * The versioning-skew case: the backend was updated to emit an int where the stored resource_type
     * still says real. Segments would store fine and every reader downstream would use the stale schema.
     */
    @Test
    void flagsSchemaDriftFromWhatWasRegistered() {
      final var gate = gate();
      gate.checkResourceProfile("Power", ValueSchema.INT);
      assertTrue(only(gate).contains("Power"), only(gate));
      assertTrue(only(gate).contains("registered as"), only(gate));
    }

    @Test
    void flagsNonFiniteDynamics() {
      final var gate = gate();
      gate.checkRealSegment("Power", 1_000L, Double.NaN, Double.POSITIVE_INFINITY);
      assertEquals(2, gate.findings().size(), gate.findings().toString());
    }

    @Test
    void flagsADiscreteValueOutsideItsVariant() {
      final var gate = gate();
      gate.checkDiscreteSegment("Mode", 1_000L, SerializedValue.of("Degraded"));
      assertTrue(only(gate).contains("not one of"), only(gate));
    }

    /** A profile longer than the simulation means we and the backend disagree about the clock. */
    @Test
    void flagsAProfileRunningPastTheSimulation() {
      final var gate = gate();
      gate.checkRealSegment("Power", SIM_DURATION_US, 0, 0);
      assertEquals(List.of(), gate.findings());
      gate.checkRealSegment("Power", 1_000L, 0, 0);
      assertTrue(only(gate).contains("past the simulation duration"), only(gate));
    }

    /** ...but only once, however many segments overrun -- one clock bug should not be thousands of lines. */
    @Test
    void reportsAnOverrunOnlyOnce() {
      final var gate = gate();
      for (var i = 0; i < 5; i++) gate.checkRealSegment("Power", SIM_DURATION_US, 0, 0);
      assertEquals(1, gate.findings().size(), gate.findings().toString());
    }

    /**
     * With nothing registered yet -- the very first introspection, or a model whose refresh failed --
     * there is no closed world to check against, and inventing one would reject every legitimate result.
     */
    @Test
    void skipsClosedWorldChecksWhenNothingIsRegistered() {
      final var gate = ExternalResultsGate.withMode(
          ExternalResultsGate.Mode.REJECT, Map.of(), Map.of(), Set.of(), SIM_DURATION_US);
      gate.checkResourceProfile("Anything", ValueSchema.REAL);
      gate.checkSpan(1, "AnyType", 0, 0L, Map.of(), null, 99L);
      assertDoesNotThrow(gate::finish);
    }
  }

  @Nested
  final class Spans {
    @Test
    void flagsAnUnregisteredActivityType() {
      final var gate = gate();
      gate.checkSpan(1, "NotAThing", 0, 0L, Map.of(), null, null);
      assertTrue(only(gate).contains("not a registered activity type"), only(gate));
    }

    /** A span may only claim a directive we sent it; anything else attaches output to a foreign plan. */
    @Test
    void flagsADirectiveIdWeNeverSent() {
      final var gate = gate();
      gate.checkSpan(1, "CollectScience", 0, 0L, Map.of("d", SerializedValue.of(1L)), null, 77L);
      assertTrue(only(gate).contains("claims directiveId 77"), only(gate));
    }

    /** A span with no directiveId is normal: decomposition children, and Blackbird's own dispatch. */
    @Test
    void acceptsAnAnonymousSpan() {
      final var gate = gate();
      gate.checkSpan(1, "CollectScience", 0, 0L, Map.of("d", SerializedValue.of(1L)), null, null);
      assertEquals(List.of(), gate.findings());
    }

    @Test
    void flagsDuplicateSpanIds() {
      final var gate = gate();
      final var args = Map.of("d", SerializedValue.of(1L));
      gate.checkSpan(5, "CollectScience", 0, 0L, args, null, null);
      gate.checkSpan(5, "CollectScience", 0, 0L, args, null, null);
      assertTrue(only(gate).contains("duplicate spanId 5"), only(gate));
    }

    @Test
    void flagsAParentThatIsNotInTheResult() {
      final var gate = gate();
      gate.checkSpan(1, "CollectScience", 0, 0L, Map.of("d", SerializedValue.of(1L)), 999L, null);
      gate.finish();
      assertTrue(only(gate).contains("parentId 999"), only(gate));
    }

    /** A cycle makes the span tree unrenderable and hangs anything that walks parents naively. */
    @Test
    void flagsAParentCycle() {
      final var gate = gate();
      final var args = Map.of("d", SerializedValue.of(1L));
      gate.checkSpan(1, "CollectScience", 0, 0L, args, 2L, null);
      gate.checkSpan(2, "CollectScience", 0, 0L, args, 1L, null);
      gate.finish();
      assertTrue(gate.findings().stream().anyMatch(f -> f.contains("parent cycle")), gate.findings().toString());
    }

    @Test
    void flagsOutOfBoundsTiming() {
      final var gate = gate();
      final var args = Map.of("d", SerializedValue.of(1L));
      gate.checkSpan(1, "CollectScience", -1, 0L, args, null, null);
      gate.checkSpan(2, "CollectScience", SIM_DURATION_US + 1, 0L, args, null, null);
      gate.checkSpan(3, "CollectScience", 0, -5L, args, null, null);
      assertEquals(3, gate.findings().size(), gate.findings().toString());
    }

    /**
     * The overrun that actually persists. Merlin clamps profiles at the simulation duration on the way
     * to Postgres, but nothing clamps spans -- a real Python-adapter run stored a Discharge span of
     * "365 days 01:06:40" inside a one-day plan, and only the profile overrun was flagged.
     */
    @Test
    void flagsASpanThatEndsPastTheSimulation() {
      final var gate = gate();
      gate.checkSpan(1, "CollectScience", SIM_DURATION_US - 1_000, 60_000_000L,
                     Map.of("d", SerializedValue.of(60_000_000L)), null, null);
      assertTrue(only(gate).contains("past the simulation"), only(gate));
    }

    /**
     * A span with no duration was still running when the simulation ended. That is a state PlanDev
     * models directly (an unfinished activity, stored with a null end), so none of the duration checks
     * apply -- including the overrun check, which is the whole point: an activity that outlives the
     * window should say so rather than claim an end the simulation never reached.
     */
    @Test
    void acceptsAnUnfinishedSpanWithNoDuration() {
      final var gate = gate(ExternalResultsGate.Mode.REJECT);
      gate.checkSpan(1, "CollectScience", SIM_DURATION_US - 1_000, null,
                     Map.of("d", SerializedValue.of(60_000_000L)), null, 1L);
      assertDoesNotThrow(gate::finish);
      assertEquals(List.of(), gate.findings());
    }

    /** An unfinished span still has to be a registered type with conforming arguments. */
    @Test
    void stillChecksAnUnfinishedSpanAgainstTheModel() {
      final var gate = gate();
      gate.checkSpan(1, "NotAThing", 0, null, Map.of(), null, null);
      assertTrue(only(gate).contains("not a registered activity type"), only(gate));
    }

    /** A span ending exactly at the simulation end is fine, not an overrun. */
    @Test
    void acceptsASpanEndingExactlyAtTheSimulationEnd() {
      final var gate = gate();
      gate.checkSpan(1, "CollectScience", SIM_DURATION_US - 60_000_000L, 60_000_000L,
                     Map.of("d", SerializedValue.of(60_000_000L)), null, null);
      assertEquals(List.of(), gate.findings());
    }

    @Test
    void checksSpanArgumentsAgainstTheDeclaredParameters() {
      final var gate = gate();
      gate.checkSpan(1, "CollectScience", 0, 0L,
                     Map.of("d", SerializedValue.of("not a duration"), "bogus", SerializedValue.of(1)), null, null);
      assertEquals(2, gate.findings().size(), gate.findings().toString());
      assertTrue(gate.findings().stream().anyMatch(f -> f.contains("not a declared parameter")), gate.findings().toString());
    }

    @Test
    void flagsAMissingRequiredParameter() {
      final var gate = gate();
      gate.checkSpan(1, "CollectScience", 0, 0L, Map.of("label", SerializedValue.of("x")), null, null);
      assertTrue(only(gate).contains("missing required parameter 'd'"), only(gate));
    }
  }

  @Nested
  final class Conformance {
    @Test
    void acceptsAnIntegerWhereRealIsExpected() {
      assertNull(ExternalResultsGate.nonconformance(SerializedValue.of(3L), ValueSchema.REAL));
    }

    @Test
    void rejectsARealWhereIntIsExpected() {
      assertNotNull(ExternalResultsGate.nonconformance(SerializedValue.of(3.5), ValueSchema.INT));
    }

    /** A schema says nothing about nullability, so guessing would create false positives. */
    @Test
    void acceptsNullAgainstAnySchema() {
      assertNull(ExternalResultsGate.nonconformance(SerializedValue.NULL, ValueSchema.INT));
    }

    @Test
    void checksSeriesElementwise() {
      final var schema = ValueSchema.ofSeries(ValueSchema.REAL);
      assertNull(ExternalResultsGate.nonconformance(
          SerializedValue.of(List.of(SerializedValue.of(1), SerializedValue.of(2.5))), schema));
      assertTrue(ExternalResultsGate.nonconformance(
          SerializedValue.of(List.of(SerializedValue.of(1), SerializedValue.of("x"))), schema).contains("at [1]"));
    }

    @Test
    void checksStructFieldsBothWays() {
      final var schema = ValueSchema.ofStruct(Map.of("x", ValueSchema.REAL, "y", ValueSchema.REAL));
      assertNull(ExternalResultsGate.nonconformance(
          SerializedValue.of(Map.of("x", SerializedValue.of(1), "y", SerializedValue.of(2))), schema));
      assertTrue(ExternalResultsGate.nonconformance(
          SerializedValue.of(Map.of("x", SerializedValue.of(1))), schema).contains("missing field 'y'"));
      assertTrue(ExternalResultsGate.nonconformance(
          SerializedValue.of(Map.of("x", SerializedValue.of(1), "y", SerializedValue.of(2), "z", SerializedValue.of(3))),
          schema).contains("unexpected field 'z'"));
    }

    /** Durations cross the wire as whole microseconds, so a fractional one is a unit bug. */
    @Test
    void requiresDurationsToBeWholeMicroseconds() {
      assertNull(ExternalResultsGate.nonconformance(SerializedValue.of(1_000L), ValueSchema.DURATION));
      assertNotNull(ExternalResultsGate.nonconformance(SerializedValue.of(1_000.5), ValueSchema.DURATION));
    }
  }

  @Nested
  final class Modes {
    @Test
    void warnDoesNotFailTheIngest() {
      final var gate = gate(ExternalResultsGate.Mode.WARN);
      gate.checkResourceProfile("Ghost", ValueSchema.REAL);
      assertDoesNotThrow(gate::finish);
    }

    @Test
    void rejectAbortsWithEveryFindingInTheMessage() {
      final var gate = gate(ExternalResultsGate.Mode.REJECT);
      gate.checkResourceProfile("Ghost", ValueSchema.REAL);
      gate.checkSpan(1, "NotAThing", 0, 0L, Map.of(), null, null);
      final var thrown = assertThrows(RuntimeException.class, gate::finish);
      assertTrue(thrown.getMessage().contains("Ghost"), thrown.getMessage());
      assertTrue(thrown.getMessage().contains("NotAThing"), thrown.getMessage());
    }

    @Test
    void offChecksNothing() {
      final var gate = ExternalResultsGate.disabled();
      gate.checkResourceProfile("Ghost", ValueSchema.REAL);
      gate.checkSpan(1, "NotAThing", -1, -1L, Map.of(), 999L, 999L);
      assertDoesNotThrow(gate::finish);
      assertEquals(List.of(), gate.findings());
    }
  }

  @Nested
  final class DeclaredTypes {
    /**
     * Resource names are emitted quoted everywhere and are only ever row values in Postgres, so spaces
     * and dots must pass. Both occur in the wild: the adapter flattens arrayed resources to {@code Name.Index},
     * and Blackbird's own example adaptation has a bin literally named {@code "my bin"}.
     */
    @Test
    void acceptsResourceNamesWithSpacesAndDots() {
      final var gate = gate();
      gate.checkDeclaredTypes(Map.of(), Map.of(
          "ResourceWithSpacesInBin.my bin", ValueSchema.REAL,
          "Vec.x", ValueSchema.REAL));
      assertEquals(List.of(), gate.findings());
    }

    @Test
    void flagsAResourceNameThatIsEmptyOrHasControlCharacters() {
      final var gate = gate();
      gate.checkDeclaredTypes(Map.of(), Map.of("", ValueSchema.REAL, "line\nbreak", ValueSchema.REAL));
      assertEquals(2, gate.findings().size(), gate.findings().toString());
    }

    /** An activity type, unlike a resource, becomes a bare TS enum member and part of an interface name. */
    @Test
    void flagsAnActivityTypeThatIsNotALegalTypescriptIdentifier() {
      final var spaced = new ActivityType(
          "Collect Science", List.of(), List.of(), ValueSchema.ofStruct(Map.of()), Optional.empty(), Optional.empty());
      final var gate = gate();
      gate.checkDeclaredTypes(Map.of("Collect Science", spaced), Map.of());
      assertTrue(only(gate).contains("will not compile"), only(gate));
    }

    /** Parameters are emitted as bare object-type keys, so the same rule applies. */
    @Test
    void flagsAParameterNameThatIsNotALegalTypescriptIdentifier() {
      final var odd = new ActivityType(
          "Odd", List.of(new Parameter("my param", ValueSchema.INT)), List.of(),
          ValueSchema.ofStruct(Map.of()), Optional.empty(), Optional.empty());
      final var gate = gate();
      gate.checkDeclaredTypes(Map.of("Odd", odd), Map.of());
      assertTrue(only(gate).contains("parameter name 'my param'"), only(gate));
    }

    @Test
    void flagsARequiredParameterThatIsNotDeclared() {
      final var orphan = new ActivityType(
          "Orphan", List.of(new Parameter("a", ValueSchema.INT)), List.of("b"),
          ValueSchema.ofStruct(Map.of()), Optional.empty(), Optional.empty());
      final var gate = gate();
      gate.checkDeclaredTypes(Map.of("Orphan", orphan), Map.of());
      assertTrue(only(gate).contains("requires parameter 'b'"), only(gate));
    }

    @Test
    void flagsAKeyThatDisagreesWithTheTypeItNames() {
      final var gate = gate();
      gate.checkDeclaredTypes(Map.of("Mislabeled", COLLECT), Map.of());
      assertTrue(only(gate).contains("declares name 'CollectScience'"), only(gate));
    }
  }

  /** The single finding, asserting there is exactly one so a test cannot pass on the wrong one. */
  private static String only(final ExternalResultsGate gate) {
    assertEquals(1, gate.findings().size(), gate.findings().toString());
    return gate.findings().get(0);
  }
}
