package gov.nasa.jpl.aerie.e2e.procedural.constraints.procedures;

import gov.nasa.ammos.aerie.procedural.constraints.Constraint;
import gov.nasa.ammos.aerie.procedural.constraints.Violations;
import gov.nasa.ammos.aerie.procedural.constraints.annotations.ConstraintProcedure;
import gov.nasa.ammos.aerie.procedural.timeline.plan.Plan;
import gov.nasa.ammos.aerie.procedural.timeline.plan.SimulationResults;
import gov.nasa.ammos.aerie.procedural.timeline.util.WithModel;
import gov.nasa.jpl.aerie.banananation.Mission;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * A simple constraint that verifies access to the mission model through the WithModel interface
 */
@ConstraintProcedure
public record ModelIntegrationConstraint() implements Constraint, WithModel<Mission> {
  @Override
  public @NotNull Violations run(@NotNull Plan plan, @NotNull SimulationResults simResults) {
    // Access the mission model through the WithModel interface
    // This should work without ClassCastException if the class loader fix is working
    final var mission = model();

    // Simple constraint: fruit should never go below 2.0
    final var lowFruit = simResults.resource(mission.fruit).lessThan(2.0);

    return Violations.inside(lowFruit.highlightTrue());
  }
}
