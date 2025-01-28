package gov.nasa.jpl.aerie.contrib.streamline.modeling.clocks;

import gov.nasa.jpl.aerie.contrib.streamline.StreamlineSystem;
import gov.nasa.jpl.aerie.merlin.framework.Registrar;
import gov.nasa.jpl.aerie.merlin.framework.junit.MerlinExtension;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static gov.nasa.jpl.aerie.contrib.streamline.StreamlineSystem.*;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Resources.currentValue;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.delay;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.HOUR;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.ZERO;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MerlinExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SimulationClockTest {
    // This time is unlikely to be a hard-coded default anywhere
    public static final Instant PLAN_START = Instant.parse("2020-01-02T03:04:05Z");

    public SimulationClockTest(final Registrar registrar) {
        StreamlineSystem.init(InitArgs.testBuilder()
                .baseRegistrar(registrar)
                .planStart(PLAN_START)
                .build());
    }

    @Test
    public void relative_clock_starts_at_zero() {
        assertEquals(ZERO, currentTime());
        assertEquals(ZERO, currentValue(simulationClock()));
    }

    @Test
    public void absolute_clock_starts_at_plan_start() {
        assertEquals(PLAN_START, currentInstant());
        assertEquals(PLAN_START, currentValue(absoluteClock()));
    }

    @Test
    public void relative_clock_advances_at_unit_rate() {
        delay(1, HOUR);
        assertEquals(Duration.of(1, HOUR), currentTime());
        assertEquals(Duration.of(1, HOUR), currentValue(simulationClock()));
        delay(1, HOUR);
        assertEquals(Duration.of(2, HOUR), currentTime());
        assertEquals(Duration.of(2, HOUR), currentValue(simulationClock()));
        delay(1, HOUR);
        assertEquals(Duration.of(3, HOUR), currentTime());
        assertEquals(Duration.of(3, HOUR), currentValue(simulationClock()));
    }

    @Test
    public void absolute_clock_advances_at_unit_rate() {
        delay(1, HOUR);
        assertEquals(PLAN_START.plus(1, ChronoUnit.HOURS), currentInstant());
        assertEquals(PLAN_START.plus(1, ChronoUnit.HOURS), currentValue(absoluteClock()));
        delay(1, HOUR);
        assertEquals(PLAN_START.plus(2, ChronoUnit.HOURS), currentInstant());
        assertEquals(PLAN_START.plus(2, ChronoUnit.HOURS), currentValue(absoluteClock()));
        delay(1, HOUR);
        assertEquals(PLAN_START.plus(3, ChronoUnit.HOURS), currentInstant());
        assertEquals(PLAN_START.plus(3, ChronoUnit.HOURS), currentValue(absoluteClock()));
    }
}
