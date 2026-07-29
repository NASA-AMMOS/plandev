package gov.nasa.ammos.plandev.e2e.procedural.scheduling.procedures;

import gov.nasa.ammos.plandev.procedural.constraints.Constraint;
import gov.nasa.ammos.plandev.procedural.constraints.Violation;
import gov.nasa.ammos.plandev.procedural.constraints.Violations;
import gov.nasa.ammos.plandev.procedural.constraints.annotations.ConstraintProcedure;
import gov.nasa.ammos.plandev.procedural.timeline.plan.EventQuery;
import gov.nasa.ammos.plandev.procedural.timeline.plan.Plan;
import gov.nasa.ammos.plandev.procedural.timeline.plan.SimulationResults;
import org.jetbrains.annotations.NotNull;

// TODO: Make parameters Lists of Strings/SourceQueries for more intricate EventQuery testing in
//        ExternalEventConstraintsTests when ValueMappers are able to be added, following the resolution of:
//          https://github.com/NASA-AMMOS/plandev/issues/1737
//          "@WithMappers does not work in procedural scheduling".
@ConstraintProcedure
public record ExternalEventPresenceConstraint(
    String eventType,
    String derivationGroup,
    String sourceKey
  ) implements Constraint {
  @NotNull
  @Override
  public Violations run(@NotNull Plan plan, @NotNull SimulationResults simResults) {
    // handle omitted sourceKey/derivationGroup/eventType (setting them to null manually, lest they be considered
    //    missing arguments
    final String NULL_VALUE = "NULL";
    final var updatedEventType = eventType.equals(NULL_VALUE) ? null : eventType;
    final var updatedDerivationGroup = derivationGroup.equals(NULL_VALUE) ? null : derivationGroup;
    final var sourceQuery =
        sourceKey.equals(NULL_VALUE) || derivationGroup.equals(NULL_VALUE)
            ? null
            : new EventQuery.SourceQuery(sourceKey, derivationGroup);

    // make the query
    var events = plan.events(
        new EventQuery(
            updatedDerivationGroup,
            updatedEventType,
            sourceQuery
        )
    );

    // if we have any events that suit the above query
    if (!events.collect().isEmpty()) {
      // ...mark those events as violating
      return new Violations(
          events.collectIntervals()
                .stream()
                .map(e -> new Violation(e.getInterval()))
                .toList()
      );
    }
    // otherwise, return an empty violations object.
    return new Violations();
  }
}
