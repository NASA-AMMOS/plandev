package gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete;

import gov.nasa.jpl.aerie.contrib.streamline.core.Resource;
import gov.nasa.jpl.aerie.contrib.streamline.core.Resources;
import gov.nasa.jpl.aerie.merlin.framework.Registrar;
import gov.nasa.jpl.aerie.merlin.framework.junit.MerlinExtension;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import static gov.nasa.jpl.aerie.contrib.streamline.core.Resources.currentValue;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteResources.precomputed;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.delay;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DiscreteResources#precomputed}
 */
@ExtendWith(MerlinExtension.class)
@TestInstance(Lifecycle.PER_CLASS)
public class PrecomputedTest {
    public PrecomputedTest(final Registrar registrar) {
        Resources.init();
    }

    final Resource<Discrete<Integer>> precomputedAsAConstant =
            precomputed(4, new TreeMap<>());
    @Test
    void precomputed_with_no_transitions_uses_default_value_forever() {
        assertEquals(4, currentValue(precomputedAsAConstant));
        delay(HOUR);
        assertEquals(4, currentValue(precomputedAsAConstant));
        delay(HOUR);
        assertEquals(4, currentValue(precomputedAsAConstant));
    }

    final Resource<Discrete<Integer>> precomputedWithOneTransitionInFuture =
            precomputed(0, new TreeMap<>(Map.of(MINUTE, 10)));
    @Test
    void precomputed_with_transition_in_future_changes_at_that_time() {
        assertEquals(0, currentValue(precomputedWithOneTransitionInFuture));
        assertTransition(precomputedWithOneTransitionInFuture, MINUTE, 10);
        delay(HOUR);
        assertEquals(10, currentValue(precomputedWithOneTransitionInFuture));
        delay(HOUR);
        assertEquals(10, currentValue(precomputedWithOneTransitionInFuture));
    }

    final Resource<Discrete<Integer>> precomputedWithOneTransitionInPast =
            precomputed(0, new TreeMap<>(Map.of(duration(-1, MINUTE), 10)));
    @Test
    void precomputed_with_transition_in_past_uses_that_value_forever() {
        assertEquals(10, currentValue(precomputedWithOneTransitionInPast));
        delay(HOUR);
        assertEquals(10, currentValue(precomputedWithOneTransitionInPast));
        delay(HOUR);
        assertEquals(10, currentValue(precomputedWithOneTransitionInPast));
    }

    final Resource<Discrete<Integer>> precomputedWithMultipleTransitionsInFuture =
            precomputed(0, new TreeMap<>(Map.of(
                    duration(2, MINUTE), 5,
                    duration(5, MINUTE), 10,
                    duration(6, MINUTE), 15)));
    @Test
    void precomputed_with_multiple_transitions_in_future_goes_through_each_in_turn() {
        assertEquals(0, currentValue(precomputedWithMultipleTransitionsInFuture));
        assertTransition(precomputedWithMultipleTransitionsInFuture, duration(2, MINUTE), 5);
        assertTransition(precomputedWithMultipleTransitionsInFuture, duration(3, MINUTE), 10);
        assertTransition(precomputedWithMultipleTransitionsInFuture, duration(1, MINUTE), 15);
        delay(HOUR);
        assertEquals(15, currentValue(precomputedWithMultipleTransitionsInFuture));
        delay(HOUR);
        assertEquals(15, currentValue(precomputedWithMultipleTransitionsInFuture));
    }

    final Resource<Discrete<Integer>> precomputedWithMultipleTransitionsInPast =
            precomputed(0, new TreeMap<>(Map.of(
                    duration(-2, MINUTE), 5,
                    duration(-5, MINUTE), 10,
                    duration(-6, MINUTE), 15)));
    @Test
    void precomputed_with_multiple_transition_in_past_uses_last_value_forever() {
        assertEquals(5, currentValue(precomputedWithMultipleTransitionsInPast));
        delay(HOUR);
        assertEquals(5, currentValue(precomputedWithMultipleTransitionsInPast));
        delay(HOUR);
        assertEquals(5, currentValue(precomputedWithMultipleTransitionsInPast));
    }

    final Resource<Discrete<Integer>> precomputedWithTransitionsInPastAndFuture =
            precomputed(0, new TreeMap<>(Map.of(
                    duration(-5, MINUTE), 25,
                    duration(-2, MINUTE), 5,
                    duration(5, MINUTE), 10,
                    duration(6, MINUTE), 15)));
    @Test
    void precomputed_with_transitions_in_past_and_future_chooses_starting_value_and_changes_later() {
        assertEquals(5, currentValue(precomputedWithTransitionsInPastAndFuture));
        assertTransition(precomputedWithTransitionsInPastAndFuture, duration(5, MINUTE), 10);
        assertTransition(precomputedWithTransitionsInPastAndFuture, duration(1, MINUTE), 15);
        delay(HOUR);
        assertEquals(15, currentValue(precomputedWithTransitionsInPastAndFuture));
        delay(HOUR);
        assertEquals(15, currentValue(precomputedWithTransitionsInPastAndFuture));
    }

    final Resource<Discrete<Integer>> precomputedWithInstantKeys =
            precomputed(0, new TreeMap<>(Map.of(
                    Instant.parse("2023-10-17T23:55:00Z"), 25,
                    Instant.parse("2023-10-17T23:58:00Z"), 5,
                    Instant.parse("2023-10-18T00:05:00Z"), 10,
                    Instant.parse("2023-10-18T00:06:00Z"), 15)),
                    Instant.parse("2023-10-18T00:00:00Z"));
    @Test
    void precomputed_with_instant_keys_behaves_identically_to_equivalent_duration_offsets() {
        assertEquals(5, currentValue(precomputedWithInstantKeys));
        assertTransition(precomputedWithInstantKeys, duration(5, MINUTE), 10);
        assertTransition(precomputedWithInstantKeys, duration(1, MINUTE), 15);
        delay(HOUR);
        assertEquals(15, currentValue(precomputedWithInstantKeys));
        delay(HOUR);
        assertEquals(15, currentValue(precomputedWithInstantKeys));
    }

    private <A> void assertTransition(Resource<Discrete<A>> resource, Duration transitionDelay, A expectedValue) {
        A startValue = currentValue(resource);
        delay(transitionDelay.minus(EPSILON));
        assertEquals(startValue, currentValue(resource));
        delay(EPSILON);
        assertEquals(expectedValue, currentValue(resource));
    }
}
