package gov.nasa.ammos.plandev.scheduler.simulation;

import gov.nasa.ammos.plandev.merlin.driver.SimulationResultsComputerInputs;
import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;
import gov.nasa.ammos.plandev.scheduler.SchedulingInterruptedException;
import gov.nasa.ammos.plandev.scheduler.model.ActivityType;
import gov.nasa.ammos.plandev.scheduler.model.Plan;
import gov.nasa.ammos.plandev.scheduler.model.SchedulingActivity;
import gov.nasa.ammos.plandev.types.ActivityDirective;
import gov.nasa.ammos.plandev.types.ActivityDirectiveId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

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
      // If the simulations have a different amount of directives, they must not be equal
      if(directiveIdActivityDirectiveMap.size() != other.directiveIdActivityDirectiveMap.size()) {
        return Optional.empty();
      }

      // Build the maps of directives -> ids from the ids -> directives map
      // Because multiple activities on a plan can be identical sans id, the inverted maps to a list of ids
      final HashMap<ActivityDirective, List<ActivityDirectiveId>> thisInverted = HashMap.newHashMap(directiveIdActivityDirectiveMap.size());
      directiveIdActivityDirectiveMap.forEach(
          (key, value) -> {
            thisInverted.putIfAbsent(value, new ArrayList<>());
            thisInverted.get(value).add(key);
          });

      final HashMap<ActivityDirective, List<ActivityDirectiveId>> otherInverted = HashMap.newHashMap(other.directiveIdActivityDirectiveMap.size());
      other.directiveIdActivityDirectiveMap.forEach(
          (key, value) -> {
            otherInverted.putIfAbsent(value, new ArrayList<>());
            otherInverted.get(value).add(key);
          });

      // If these maps have different sizes, then the simulations must not be equal
      if(thisInverted.size() != otherInverted.size()) {
        return Optional.empty();
      }

      // Check every entry for equality while building up the return mapping
      final var result = new HashMap<ActivityDirectiveId, ActivityDirectiveId>();
      for (final var entry : thisInverted.entrySet()) {
        // Check that at least one instance of this directive is on the other plan
        if(!otherInverted.containsKey(entry.getKey())){
          return Optional.empty();
        }

        final var thisIds = entry.getValue();
        final var otherIds = otherInverted.get(entry.getKey());

        // Check that there is the same number of instances of this directive on the other plan
        if (thisIds.size() != otherIds.size()) {
          return Optional.empty();
        }

        // If the directive is unique (the most common case), then the two ids can be directly compared
        if(thisIds.size() == 1) {
          if(!thisIds.getFirst().equals(otherIds.getFirst())) {
            result.put(thisIds.getFirst(), otherIds.getFirst());
          }
          continue;
        }

        // Filter out the ids that are on both lists, as this method does not provide a mapping for ids that have not changed
        final List<ActivityDirectiveId> toRemove = thisIds.stream().filter(otherIds::contains).toList();
        thisIds.removeAll(toRemove);
        otherIds.removeAll(toRemove);

        // Add the remaining mappings to the result
        for(int i = 0; i < thisIds.size(); ++i) {
          result.put(thisIds.get(i), otherIds.get(i));
        }
      }

      return Optional.of(result);
    }
  }
}
