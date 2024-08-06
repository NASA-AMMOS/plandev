package gov.nasa.ammos.aerie.procedural.examples.fooprocedures.procedures;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.ammos.aerie.procedural.scheduling.Rule;
import gov.nasa.ammos.aerie.procedural.scheduling.annotations.SchedulingProcedure;
import gov.nasa.ammos.aerie.procedural.scheduling.plan.EditablePlan;
import gov.nasa.ammos.aerie.procedural.timeline.collections.profiles.Real;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.DirectiveStart;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Map;

@SchedulingProcedure
public record SimulationDemo(int quantity) implements Rule {
  @Override
  public void run(EditablePlan plan) {
//    final var firstActivityTime = plan.toRelative(Instant.from(DOY_WITHOUT_ZONE_FORMATTER.parse("2024-128T07:00:00")));
//
//    plan.create(
//        "BiteBanana",
//        new DirectiveStart.Absolute(firstActivityTime),
//        Map.of("biteSize", SerializedValue.of(2))
//    );

    var simResults = plan.latestResults();
    if (simResults == null) simResults = plan.simulate();

    final var lowFruit = simResults.resource("/fruit", Real::deserialize).lessThan(3.5).isolateTrue();
    final var bites = simResults.instances("BiteBanana");

    final var connections = lowFruit.starts().shift(Duration.MINUTE.negate())
                                    .connectTo(bites.ends(), false);

    for (final var connection: connections.collect()) {
      assert connection.to != null;
      plan.create(
          "GrowBanana",
          new DirectiveStart.Anchor(
              connection.to.directiveId,
              Duration.minutes(30),
              DirectiveStart.Anchor.AnchorPoint.End
          ),
          Map.of(
              "quantity", SerializedValue.of(1),
              "growingDuration", SerializedValue.of(Duration.HOUR.dividedBy(Duration.MICROSECOND))
          )
      );
    }

    plan.commit();
  }

  private static final DateTimeFormatter DOY_WITHOUT_ZONE_FORMATTER = new DateTimeFormatterBuilder()
      .appendPattern("uuuu-DDD'T'HH:mm:ss")
      .appendFraction(ChronoField.MICRO_OF_SECOND, 0, 6, true)
      .toFormatter()
      .withZone(ZoneOffset.UTC);
}
