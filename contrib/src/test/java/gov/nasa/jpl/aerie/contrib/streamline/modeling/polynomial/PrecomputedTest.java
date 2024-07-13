package gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial;

import gov.nasa.jpl.aerie.contrib.streamline.core.Resource;
import gov.nasa.jpl.aerie.contrib.streamline.core.Resources;
import gov.nasa.jpl.aerie.merlin.framework.junit.MerlinExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import static gov.nasa.jpl.aerie.contrib.streamline.core.Resources.currentValue;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.PolynomialResources.precomputed;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.delay;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PolynomialResources#precomputed}
 */
@ExtendWith(MerlinExtension.class)
@TestInstance(Lifecycle.PER_CLASS)
public class PrecomputedTest {
    {
        // We need to initialize this up front, so we can use in-line initializers for other resources after.
        // I think in-line initializers for the other resources make the tests easier to read.
        Resources.init(Instant.EPOCH);
    }

    final Resource<Polynomial> precomputedAsConstantInPast =
            precomputed(new TreeMap<>(Map.of(duration(-1, MINUTE), 4.0)));
    @Test
    void precomputed_with_single_point_in_past_extrapolates_that_value_forever() {
        assertValueEquals(4.0, precomputedAsConstantInPast);
        delay(HOUR);
        assertValueEquals(4.0, precomputedAsConstantInPast);
        delay(HOUR);
        assertValueEquals(4.0, precomputedAsConstantInPast);
    }

    final Resource<Polynomial> precomputedAsConstantInFuture =
            precomputed(new TreeMap<>(Map.of(duration(2, HOUR), 4.0)));
    @Test
    void precomputed_with_single_point_in_future_extrapolates_that_value_forever() {
        assertValueEquals(4.0, precomputedAsConstantInFuture);
        delay(HOUR);
        assertValueEquals(4.0, precomputedAsConstantInFuture);
        delay(HOUR);
        assertValueEquals(4.0, precomputedAsConstantInFuture);
        delay(HOUR);
        assertValueEquals(4.0, precomputedAsConstantInFuture);
        delay(HOUR);
        assertValueEquals(4.0, precomputedAsConstantInFuture);
    }

    final Resource<Polynomial> precomputedWithSingleInteriorSegmentInPast =
            precomputed(new TreeMap<>(Map.of(
                    duration(-100, SECOND), 0.0,
                    duration(-50, SECOND), 5.0)));
    @Test
    void precomputed_with_single_interior_segment_in_past_extrapolates_final_value() {
        assertValueEquals(5.0, precomputedWithSingleInteriorSegmentInPast);
        delay(HOUR);
        assertValueEquals(5.0, precomputedWithSingleInteriorSegmentInPast);
        delay(HOUR);
        assertValueEquals(5.0, precomputedWithSingleInteriorSegmentInPast);
    }

    final Resource<Polynomial> precomputedWithSingleInteriorSegmentInFuture =
            precomputed(new TreeMap<>(Map.of(
                    duration(50, SECOND), 0.0,
                    duration(100, SECOND), 5.0)));
    @Test
    void precomputed_with_single_interior_segment_in_future_interpolates_that_segment() {
        assertValueEquals(0.0, precomputedWithSingleInteriorSegmentInFuture);
        delay(50, SECOND);
        assertValueEquals(0.0, precomputedWithSingleInteriorSegmentInFuture);
        delay(10, SECOND);
        assertValueEquals(1.0, precomputedWithSingleInteriorSegmentInFuture);
        delay(10, SECOND);
        assertValueEquals(2.0, precomputedWithSingleInteriorSegmentInFuture);
        delay(10, SECOND);
        assertValueEquals(3.0, precomputedWithSingleInteriorSegmentInFuture);
        delay(10, SECOND);
        assertValueEquals(4.0, precomputedWithSingleInteriorSegmentInFuture);
        delay(10, SECOND);
        assertValueEquals(5.0, precomputedWithSingleInteriorSegmentInFuture);
        delay(HOUR);
        assertValueEquals(5.0, precomputedWithSingleInteriorSegmentInFuture);
    }

    final Resource<Polynomial> precomputedStartingInInterior =
            precomputed(new TreeMap<>(Map.of(
                    duration(-50, SECOND), 0.0,
                    duration(50, SECOND), 10.0)));
    void precomputed_starting_in_interior_interpolates_over_full_segment() {
        assertValueEquals(5.0, precomputedStartingInInterior);
        delay(10, SECOND);
        assertValueEquals(6.0, precomputedStartingInInterior);
        delay(10, SECOND);
        assertValueEquals(7.0, precomputedStartingInInterior);
        delay(10, SECOND);
        assertValueEquals(8.0, precomputedStartingInInterior);
        delay(10, SECOND);
        assertValueEquals(9.0, precomputedStartingInInterior);
        delay(10, SECOND);
        assertValueEquals(10.0, precomputedStartingInInterior);
        delay(HOUR);
        assertValueEquals(10.0, precomputedStartingInInterior);
    }

    final Resource<Polynomial> precomputedWithMultipleSegments =
            precomputed(new TreeMap<>(Map.of(
                    duration(-50, SECOND), 0.0,
                    duration(50, SECOND), 10.0,
                    duration(60, SECOND), 30.0,
                    duration(90, SECOND), -30.0)));
    @Test
    void precomputed_with_multiple_segments_interpolates_each_segment_independently() {
        assertValueEquals(5.0, precomputedWithMultipleSegments);
        delay(25, SECOND);
        assertValueEquals(7.5, precomputedWithMultipleSegments);
        delay(25, SECOND);
        assertValueEquals(10.0, precomputedWithMultipleSegments);
        delay(5, SECOND);
        assertValueEquals(20.0, precomputedWithMultipleSegments);
        delay(5, SECOND);
        assertValueEquals(30.0, precomputedWithMultipleSegments);
        delay(10, SECOND);
        assertValueEquals(10.0, precomputedWithMultipleSegments);
        delay(10, SECOND);
        assertValueEquals(-10.0, precomputedWithMultipleSegments);
        delay(10, SECOND);
        assertValueEquals(-30.0, precomputedWithMultipleSegments);
    }

    private static final double TOLERANCE = 1e-13;
    private static final double EPSILON = 1e-10;
    private void assertValueEquals(double expected, Resource<Polynomial> resource) {
        assertTrue(Math.abs(expected - currentValue(resource)) / (expected + EPSILON) < TOLERANCE,
                "Resource value equals " + expected);
    }
}
