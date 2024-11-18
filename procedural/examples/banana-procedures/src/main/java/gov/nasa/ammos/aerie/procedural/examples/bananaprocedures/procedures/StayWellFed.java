package gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.procedures;

import gov.nasa.ammos.aerie.procedural.scheduling.Goal;
import gov.nasa.ammos.aerie.procedural.scheduling.annotations.SchedulingProcedure;
import gov.nasa.ammos.aerie.procedural.scheduling.plan.EditablePlan;
import gov.nasa.ammos.aerie.procedural.timeline.Interval;
import gov.nasa.ammos.aerie.procedural.timeline.collections.profiles.Real;
import gov.nasa.ammos.aerie.procedural.timeline.collections.profiles.Strings;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.LinearEquation;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.Segment;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.DirectiveStart;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * This goal creates regularly spaced Bite Banana activities (to stay well-fed).
 * This will cause the /fruit resource to drop far below zero, so it also creates
 * Grow Banana activities to fix that.
 *
 * More detailed algorithm:
 * 1. The goal is only applied when /producer == Dole. This is analogous to a mission phase.
 * 2. During the Dole phase, place a Bite Banana at most every `bitePeriodHours` apart.
 *    This step accounts for existing Bite Bananas and is essentially a cheap reimplementation
 *    of Recurrence Goal.
 * 3. Resimulate to see the effects this has on the /fruit resource
 * 4. We now want to prevent /fruit from dropping below 0. We could do this by placing
 *    a Grow Banana before the first time it becomes negative, and then resimulate, but
 *    that would take a lot of simulations. So instead, we place the activity and then
 *    mock the effect model of Grow Banana by adding a slanted step function to /fruit;
 *    then iterate.
 *
 *    Since we are doing this all in one goal, we know that we just created these Bites,
 *    and can safely anchor the new Grows to the Bites, if there is one. So at each
 *    `/fruit < 0` point, we query for a bite banana and anchor to it if possible.
 * @param bitePeriodHours
 */
@SchedulingProcedure
public record StayWellFed(double bitePeriodHours) implements Goal {
  @Override
  public void run(@NotNull final EditablePlan plan) {
    final var bitePeriod = Duration.hours(bitePeriodHours);
    var simResults = plan.latestResults();
    if (simResults == null) simResults = plan.simulate();

    // I'm using producer as a substitute for a mission phase variable.
    // This goal will only apply during the Dole mission phase. :)
    final var dolePhase = simResults
        .resource("/producer", Strings.deserializer())
        .highlightEqualTo("Dole")
        .cache();

    // Manual recurrence goal: during Dole phase, require a bitebanana every `bitePeriod`.
    final var bites = plan.directives("BiteBanana")
        .filterByWindows(dolePhase, false)
        .collect();

    var currentTime = Duration.MIN_VALUE;
    for (final var phase: dolePhase) {
      currentTime = Duration.max(currentTime, phase.start);

      while (currentTime.plus(bitePeriod).shorterThan(phase.end)) {
        var nextExistingBiteTime = bites.isEmpty() ? Duration.MAX_VALUE : bites.getFirst().getStartTime();
        while (nextExistingBiteTime.minus(currentTime).noLongerThan(bitePeriod)) {
          currentTime = Duration.max(currentTime, nextExistingBiteTime);
          bites.removeFirst();

          nextExistingBiteTime = bites.isEmpty() ? Duration.MAX_VALUE : bites.getFirst().getStartTime();
        }
        while (nextExistingBiteTime.minus(currentTime).longerThan(bitePeriod) && phase.contains(currentTime.plus(bitePeriod))) {
          currentTime = currentTime.plus(bitePeriod);
          plan.create(
              "BiteBanana",
              new DirectiveStart.Absolute(currentTime),
              Map.of("biteSize", SerializedValue.of(1))
          );
        }
      }
    }

    plan.commit();
    simResults = plan.simulate();

    final var newBites = plan.directives("BiteBanana")
        .filterByWindows(dolePhase, false);

    // All this banana biting made us run out of bananas.
    // So we iteratively find the first time /fruit drops below zero
    // and add a grow banana fix it. We then mock the effect of grow banana
    // by adding one to /fruit, rather than resimulating, and do it again.
    var fruit = simResults.resource("/fruit", Real.deserializer()).cache();

    var ranOutAt = fruit.lessThan(0).filterByWindows(dolePhase, true).risingEdges().highlightTrue().collect();
    while (!ranOutAt.isEmpty()) {
      final var problemStart = ranOutAt.getFirst().start;
      final var growStart = problemStart.minus(Duration.HOUR);
      final var activeBites = newBites.collect(Interval.at(problemStart));

      final var currentFruit = fruit.sample(problemStart);
      final var pastFruit = fruit.sample(growStart);

      final var growQuantity = Math.ceil(pastFruit - currentFruit);

      plan.create(
          "GrowBanana",
          activeBites.isEmpty() ? new DirectiveStart.Absolute(growStart)
              : new DirectiveStart.Anchor(activeBites.getFirst().id, Duration.HOUR.negate(), DirectiveStart.Anchor.AnchorPoint.Start),
          Map.of(
              "growingDuration", SerializedValue.of(Duration.HOUR.micros()),
              "quantity", SerializedValue.of(growQuantity)
          )
      );

      fruit = fruit.plus(
          Real.step(growStart, growQuantity)
              .set(new Real(List.of(
                  new Segment<>(Interval.between(growStart, problemStart), new LinearEquation(growStart, 0.0, growQuantity / (Duration.HOUR.in(Duration.SECONDS))))
              )))
      );

      ranOutAt = fruit.lessThan(0).filterByWindows(dolePhase, true).risingEdges().highlightTrue().collect();
    }

    plan.commit();
  }
}
