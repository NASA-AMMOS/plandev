package gov.nasa.jpl.aerie.scheduler.simulation;

import gov.nasa.jpl.aerie.merlin.driver.SimulationResultsComputerInputs;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.scheduler.SchedulingInterruptedException;
import gov.nasa.jpl.aerie.scheduler.model.ActivityType;
import gov.nasa.jpl.aerie.scheduler.model.Plan;
import gov.nasa.jpl.aerie.scheduler.model.SchedulingActivity;
import gov.nasa.jpl.aerie.types.ActivityDirective;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public interface SimulationFacade {
  void setInitialSimResults(SimulationData simulationData);

  Duration totalSimulationTime();

  Supplier<Boolean> getCanceledListener();

  void addActivityTypes(Collection<ActivityType> activityTypes);

  SimulationResultsComputerInputs simulateNoResultsAllActivities(Plan plan)
  throws SimulationException, SchedulingInterruptedException;

  SimulationResultsComputerInputs simulateNoResultsUntilEndAct(
      Plan plan,
      SchedulingActivity activity) throws SimulationException, SchedulingInterruptedException;

  AugmentedSimulationResultsComputerInputs simulateNoResults(
      Plan plan,
      Duration until) throws SimulationException, SchedulingInterruptedException;

  SimulationData simulateWithResults(
      Plan plan,
      Duration until) throws SimulationException, SchedulingInterruptedException;

  SimulationData simulateWithResults(
      Plan plan,
      Duration until,
      Set<String> resourceNames) throws SimulationException, SchedulingInterruptedException;

  Optional<SimulationData> getLatestSimulationData();

  class SimulationException extends Exception {
    SimulationException(final String message, final Throwable cause) {
      super(message, cause);
    }
  }

  record AugmentedSimulationResultsComputerInputs(
      SimulationResultsComputerInputs simulationResultsComputerInputs,
      SimulationFacade.PlanSimCorrespondence planSimCorrespondence
  ) {}

  record PlanSimCorrespondence(
      Map<ActivityDirectiveId, ActivityDirective> directiveIdActivityDirectiveMap){
    @Override
    public boolean equals(Object other){
      if(other instanceof PlanSimCorrespondence planSimCorrespondenceAs){
        return directiveIdActivityDirectiveMap.size() == planSimCorrespondenceAs.directiveIdActivityDirectiveMap.size() &&
               new HashSet<>(directiveIdActivityDirectiveMap.values()).containsAll(new HashSet<>(((PlanSimCorrespondence) other).directiveIdActivityDirectiveMap.values()));
      }
      return false;
    }

    /**
     * Performs an ID-agnostic equals check, but also generates a transformation for IDs that do not
     * match between the two plans.
     *
     * This function is anti-symmetric; `a.equalsWithIdMap(b)` will return a map from `a` ids to `b` ids, which is the
     * reverse of `b.equalsWithIdMap(a)`.
     *
     * @return Either `Optional.empty` if the plans are not equal, or `Optional.of(map)` if they are equal, where
     * `map` contains a mapping between *only the IDs that are different* between the two plans.
     */
    public Optional<Map<ActivityDirectiveId, ActivityDirectiveId>> equalsWithIdMap(PlanSimCorrespondence other) {
      final var result = new HashMap<ActivityDirectiveId, ActivityDirectiveId>();
      final var thisInverse = directiveIdActivityDirectiveMap.entrySet()
                                 .stream()
                                 .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
      final var otherInverse = other.directiveIdActivityDirectiveMap.entrySet()
                                                             .stream()
                                                             .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
      for (var entry : thisInverse.entrySet()) {
        final var otherId = otherInverse.remove(entry.getKey());
        if (otherId == null) {
          return Optional.empty();
        }
        if (!otherId.equals(entry.getValue())) {
          result.put(entry.getValue(), otherId);
        }
      }

      if (otherInverse.isEmpty()) {
        return Optional.of(result);
      } else {
        return Optional.empty();
      }
    }
  }
}
