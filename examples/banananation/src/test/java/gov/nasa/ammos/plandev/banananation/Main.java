package gov.nasa.jpl.aerie.banananation;

import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.types.SerializedActivity;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Map;

import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.MILLISECONDS;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECONDS;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.duration;

public final class Main {
  public static void main(final String[] args) {
    final var schedule = SimulationUtility.buildSchedule(
        Pair.of(
            duration(0, MILLISECONDS),
            new SerializedActivity("PeelBanana", Map.of("peelDirection", SerializedValue.of("fromStem")))),
        Pair.of(
            duration(300, MILLISECONDS),
            new SerializedActivity("BiteBanana", Map.of("biteSize", SerializedValue.of(1.5)))),
        Pair.of(
            duration(700, MILLISECONDS),
            new SerializedActivity("BiteBanana", Map.of("biteSize", SerializedValue.of(0.5)))),
        Pair.of(
            duration(1100, MILLISECONDS),
            new SerializedActivity("BiteBanana", Map.of("biteSize", SerializedValue.of(2.0)))),
        Pair.of(
            duration(1500, MILLISECONDS),
            new SerializedActivity("BakeBananaBread", Map.of(
                "tbSugar", SerializedValue.of(42),
                "glutenFree", SerializedValue.of(true)))),
        Pair.of(
            duration(1900, MILLISECONDS),
            new SerializedActivity("BiteBanana", Map.of())));

    final var simulationDuration = duration(5, SECONDS);

    final var simulationResults = SimulationUtility.simulate(schedule, simulationDuration);

    System.out.println(simulationResults.discreteProfiles);
    System.out.println(simulationResults.realProfiles);
  }
}
