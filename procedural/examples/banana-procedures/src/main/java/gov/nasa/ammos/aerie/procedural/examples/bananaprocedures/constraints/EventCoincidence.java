package gov.nasa.ammos.aerie.procedural.examples.bananaprocedures.constraints;

import gov.nasa.ammos.aerie.procedural.constraints.Constraint;
import gov.nasa.ammos.aerie.procedural.constraints.GeneratorConstraint;
import gov.nasa.ammos.aerie.procedural.constraints.Violation;
import gov.nasa.ammos.aerie.procedural.constraints.Violations;
import gov.nasa.ammos.aerie.procedural.constraints.annotations.ConstraintProcedure;
import gov.nasa.ammos.aerie.procedural.timeline.collections.ExternalEvents;
import gov.nasa.ammos.aerie.procedural.timeline.collections.profiles.Booleans;
import gov.nasa.ammos.aerie.procedural.timeline.collections.profiles.Real;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.ExternalEvent;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.AnyDirective;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.AnyInstance;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.Directive;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.Instance;
import gov.nasa.ammos.aerie.procedural.timeline.plan.Plan;
import gov.nasa.ammos.aerie.procedural.timeline.plan.SimulationResults;
import gov.nasa.jpl.aerie.types.ActivityDirective;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

// verifies that every event is coincident with a PickBanana activity, but NOT a PeelBanana activity
@ConstraintProcedure
public record EventCoincidence() implements Constraint {

  @NotNull
  @Override
  public Violations run(@NotNull final Plan plan, @NotNull final SimulationResults simResults) {List<ExternalEvent> events = plan.events().collect();
    List<Instance<AnyInstance>> activityDirectives = simResults.instances().collect();
    List<Violation> violations = new ArrayList<>();

    for (var event : events) {
      // if event has no activities starting at the same time -> false
      boolean violated = true;
      for (var activity : activityDirectives) {
        if (event.getInterval().start.equals(activity.getInterval().start)) {
          // if event has anything else starting at same time -> true
          violated = false;

          // if event has a PickBanana starting at same time -> false
          if (activity.getType().equals("PickBanana")) {
            violated = true;
            break;
          }
        }
      }

      if (violated) {
        violations.add(new Violation(event.getInterval()));
      }
    }

    return new Violations(violations);
  }
}
