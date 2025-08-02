package gov.nasa.jpl.aerie.constraints.model;

import gov.nasa.ammos.aerie.procedural.constraints.Violations;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.ActivityInstanceId;

import java.util.ArrayList;
import java.util.List;

public record Violation(List<Interval> windows, ArrayList<Long> activityInstanceIds) {
  public Violation(List<Interval> windows, List<Long> activityInstanceIds) {
    this(windows, new ArrayList<>(activityInstanceIds));
  }

  public static List<Violation> fromProceduralViolations(Violations violations, gov.nasa.jpl.aerie.merlin.driver.SimulationResults simResults) {
    final var proceduralViolations = violations.collect();
    final ArrayList<Violation> constraintViolations = new ArrayList<>(proceduralViolations.size());
    for(final var v : proceduralViolations) {
      final List<Long> activityInstanceIds = new ArrayList<>(v.getIds().size());
      for(final var id : v.getIds()) {
        switch (id) {
          case ActivityDirectiveId dId -> simResults.simulatedActivities
                                                    .entrySet()
                                                    .stream()
                                                    .filter(e -> e.getValue().directiveId().isPresent()
                                                                 && e.getValue().directiveId().get().id() == dId.id())
                                                    .findFirst()
                                                    .ifPresentOrElse(e -> activityInstanceIds.add(e.getKey().id()),
                                                                     ()-> {throw new RuntimeException(
                                                                         "Activity instance with activity directive id "
                                                                         +dId.id()+" not present in simulation results.");});
          case ActivityInstanceId aId -> {
            if (simResults.simulatedActivities.containsKey(aId)) {
              activityInstanceIds.add(aId.id());
              break;
            }
            throw new RuntimeException("Activity instance with activity directive id "
                                       +aId.id()+" not present in simulation results.");
          }
          default -> throw new IllegalStateException("Unexpected type: " + id.getClass());
        }
      }

      constraintViolations.add(new Violation(List.of(Interval.fromProceduralInterval(v.getInterval())), activityInstanceIds));
    }
    return constraintViolations;
  }

  public void addActivityId(final long activityId) {
    this.activityInstanceIds.add(0, activityId);
  }
}
