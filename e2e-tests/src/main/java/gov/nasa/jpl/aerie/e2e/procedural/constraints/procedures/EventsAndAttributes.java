package gov.nasa.jpl.aerie.e2e.procedural.constraints.procedures;

import gov.nasa.ammos.aerie.procedural.constraints.Constraint;
import gov.nasa.ammos.aerie.procedural.constraints.Violations;
import gov.nasa.ammos.aerie.procedural.constraints.annotations.ConstraintProcedure;
import gov.nasa.ammos.aerie.procedural.timeline.collections.profiles.Real;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.ExternalEvent;
import gov.nasa.ammos.aerie.procedural.timeline.plan.EventQuery;
import gov.nasa.ammos.aerie.procedural.timeline.plan.Plan;
import gov.nasa.ammos.aerie.procedural.timeline.plan.SimulationResults;
import jdk.jfr.Event;
import org.jetbrains.annotations.NotNull;

/**
 * As discussed in EventCounter.java, there isn't much to delve into with regards to events and their relationship to
 *    resources and activities in constraints, or at least in our examples for testing. Here, for simplicity, we just
 *    focus on accessing and constraining event properties without introducing the complexity of events and resources,
 *    and examine whether this constraint behaves as follows. This one constraint will access all attributes of an event
 *    and in doing so demonstrate the procedural constraint engine's total access of external event functionality.
 */
@ConstraintProcedure
public record EventsAndAttributes(int eventAttributeCount, int sourceAttributeCount) implements Constraint {
  @NotNull
  @Override
  public Violations run(@NotNull Plan plan, @NotNull SimulationResults simResults) {

    // grab all events
    var events = plan.events().collect();

    // query how many have a given attribute (code) equal to a given value (A)
    int eAttributeCount = 0;
    for (ExternalEvent e : events) {
      var attributeValue = e.attributes.get("code");
      if (attributeValue != null
          && attributeValue.asString().isPresent()
          && attributeValue.asString().get().equals("A")) {
        eAttributeCount++;
      }
    }

    // query how many events have a source with the optional attribute set
    int sAttributeCount = 0;
    for (ExternalEvent e : events) {
      var sourceValue = e.source.attributes.get("optional");
      if (sourceValue != null
          && sourceValue.asString().isPresent()) {
        sAttributeCount++;
      }
    }

    return Violations.on(
        new Real(eAttributeCount).equalTo(eventAttributeCount)
            .and(new Real(sAttributeCount).equalTo(sourceAttributeCount)),
        false
    );
  }
}
