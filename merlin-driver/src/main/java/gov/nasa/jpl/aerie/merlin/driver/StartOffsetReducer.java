package gov.nasa.jpl.aerie.merlin.driver;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.types.ActivityDirective;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RecursiveTask;


public class StartOffsetReducer extends RecursiveTask<HashMap<ActivityDirectiveId, List<Pair<ActivityDirectiveId, Duration>>>> {
  private final Duration planDuration;
  private final Map<ActivityDirectiveId, ActivityDirective> completeMapOfDirectives;
  private final Map<ActivityDirectiveId, ActivityDirective> activityDirectivesToProcess;

  public StartOffsetReducer(Duration planDuration, Map<ActivityDirectiveId, ActivityDirective> activityDirectives){
    this.planDuration = planDuration;
    if(activityDirectives == null) {
      this.completeMapOfDirectives = Map.of();
      this.activityDirectivesToProcess = Map.of();
    } else {
      this.completeMapOfDirectives = activityDirectives;
      this.activityDirectivesToProcess = activityDirectives;
    }
  }

  private StartOffsetReducer(
      Duration planDuration,
      Map<ActivityDirectiveId, ActivityDirective> activityDirectives,
      Map<ActivityDirectiveId, ActivityDirective> allActivityDirectives){
    this.planDuration = planDuration;
    this.activityDirectivesToProcess = activityDirectives;
    this.completeMapOfDirectives = allActivityDirectives;
  }

  /**
   * The complexity of compute() is ~O(NL), where N is the number of activities and L is the length of the longest chain
   * In general, we expect L to be small.
   */
  @Override
  public HashMap<ActivityDirectiveId, List<Pair<ActivityDirectiveId, Duration>>> compute() {
    final var toReturn = new HashMap<ActivityDirectiveId, List<Pair<ActivityDirectiveId, Duration>>>();
    // If we have 400 or fewer activities to process, process them directly
    if(activityDirectivesToProcess.size() <= 400) {
      for (final var entry : activityDirectivesToProcess.entrySet()){
        final var dependingActivity = getNetOffset(entry.getValue());
        toReturn.putIfAbsent(dependingActivity.getLeft(), new ArrayList<>());
        toReturn.get(dependingActivity.getLeft()).add(Pair.of(entry.getKey(), dependingActivity.getValue()));
      }
      return toReturn;
    }
    // else split the map in half and process each side in parallel
    final var leftDirectivesToProcess = new HashMap<ActivityDirectiveId, ActivityDirective>(activityDirectivesToProcess.size()/2);
    final var rightDirectivesToProcess = new HashMap<ActivityDirectiveId, ActivityDirective>(activityDirectivesToProcess.size()/2);
    int count=0;
    for(var entry : activityDirectivesToProcess.entrySet()) {
      (count<(activityDirectivesToProcess.size()/2) ? leftDirectivesToProcess : rightDirectivesToProcess).put(entry.getKey(), entry.getValue());
      count++;
    }
    final var left = new StartOffsetReducer(planDuration, leftDirectivesToProcess, completeMapOfDirectives);
    final var right = new StartOffsetReducer(planDuration, rightDirectivesToProcess, completeMapOfDirectives);
    right.fork();
    // join step
    final var leftReturn = left.compute();
    final var rightReturn = right.join();

    leftReturn.forEach((key , value) -> {
      final var list = toReturn.get(key);
      if (list == null) { toReturn.put(key,value); }
      else {
        toReturn.get(key).addAll(value); // There are no duplicate entries in the lists to be merged.
      }
    });

    rightReturn.forEach((key , value) -> {
      final var list = toReturn.get(key);
      if (list == null) { toReturn.put(key,value); }
      else {
        toReturn.get(key).addAll(value); // There are no duplicate entries in the lists to be merged.
      }
    });

    return toReturn;
  }


  /**
   * Gets the greatest net offset of a given ActivityDirective
   * Base cases:
   *    1) Activity is anchored to plan
   *    2) Activity is anchored to the end time of another activity
   * @param ad The ActivityDirective currently under consideration
   * @return A Pair containing:
   *   ActivityDirectiveID: the ID of the activity that must finish being simulated before we can simulate the specified activity
   *   Duration: the net start offset from that ID
   */
  private Pair<ActivityDirectiveId, Duration> getNetOffset(ActivityDirective ad){
    ActivityDirective currentActivityDirective;
    ActivityDirectiveId currentAnchorId = ad.anchorId();
    boolean anchoredToStart = ad.anchoredToStart();
    Duration netOffset = ad.startOffset();

    final var firstId = currentAnchorId;

    while(currentAnchorId != null && anchoredToStart){
      currentActivityDirective = completeMapOfDirectives.get(currentAnchorId);
      currentAnchorId = currentActivityDirective.anchorId();

      if (currentAnchorId.equals(firstId)) {
        throw new IllegalStateException("Anchor ID cycle detected on " + firstId);
      }

      anchoredToStart = currentActivityDirective.anchoredToStart();
      netOffset = netOffset.plus(currentActivityDirective.startOffset());
    }

    if(currentAnchorId == null && !anchoredToStart) {
      return Pair.of(null, planDuration.plus(netOffset)); // Add plan duration if anchored to plan end for net
    }
    return Pair.of(currentAnchorId, netOffset);
  }

  /**
   * Takes a List of Pairs of ActivityDirectiveIds and Durations, and returns a new List where the Durations have been uniformly adjusted.
   *
   * This will generally exclusively be called with the values mapped to the `null` key, in order to correct for the difference between plan startTime and simulation startTime.
   *
   * @param original The list to be used as reference.
   * @param difference The amount to subtract from the Duration of each entry in original.
   * @return A new List with the updated Durations.
   */
  public static List<Pair<ActivityDirectiveId, Duration>> adjustStartOffset(List<Pair<ActivityDirectiveId, Duration>> original, Duration difference) {
    if(original == null) return null;
    if(difference == null) throw new NullPointerException("Cannot adjust start offset because \"difference\" is null.");
    return original.stream().map( pair -> Pair.of(pair.getKey(), pair.getValue().minus(difference))).toList();
  }

  /**
   * Takes a Hashmap and filters out all activities with a negative start offset, as well as any activities depending on the activities that were filtered out (and so on).
   *
   * @param toFilter The HashMap to be filtered.
   * @return A new HashMap that has been appropriately filtered.
   */
  public static HashMap<ActivityDirectiveId, List<Pair<ActivityDirectiveId, Duration>>> filterOutNegativeStartOffset(HashMap<ActivityDirectiveId, List<Pair<ActivityDirectiveId, Duration>>> toFilter) {
    return filterOutStartOffsetBefore(toFilter, Duration.ZERO);
  }

  public static HashMap<ActivityDirectiveId, List<Pair<ActivityDirectiveId, Duration>>> filterOutStartOffsetBefore(
      final HashMap<ActivityDirectiveId, List<Pair<ActivityDirectiveId, Duration>>> toFilter,
      final Duration duration)
  {
    if(toFilter == null) return null;

    // Create a deep copy of toFilter (The Pairs are immutable, so they do not need to be copied)
    final var filtered = new HashMap<ActivityDirectiveId, List<Pair<ActivityDirectiveId, Duration>>>(toFilter.size());
    for(final var key : toFilter.keySet()){
      filtered.put(key, new ArrayList<>(toFilter.get(key)));
    }

    if(!toFilter.containsKey(null)){
      if(!toFilter.isEmpty()) {
        throw new RuntimeException("None of the activities in \"toFilter\" are anchored to the plan");
      }
      return filtered;
    }

    final var beforeStartTime = new ArrayList<>(toFilter
                                                    .get(null)
                                                    .stream()
                                                    .filter(pair -> pair.getValue().shorterThan(duration))
                                                    .toList());
    while(!beforeStartTime.isEmpty()){
      final Pair<ActivityDirectiveId, Duration> currentPair = beforeStartTime.removeLast();
      if(filtered.containsKey(currentPair.getLeft())) {
        beforeStartTime.addAll(filtered.get(currentPair.getLeft()));
        filtered.remove(currentPair.getLeft());
      }
    }
    filtered.get(null).removeIf(pair -> pair.getValue().shorterThan(duration));
    return filtered;
  }
}

