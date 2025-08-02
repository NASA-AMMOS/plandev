package gov.nasa.jpl.aerie.constraints.model;

import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class SimulationResults {
  public final Instant planStart;
  public final Interval bounds;
  public final List<ActivityInstance> activities;
  public final Map<String, LinearProfile> realProfiles;
  public final Map<String, DiscreteProfile> discreteProfiles;

  public SimulationResults(
      final Instant planStart,
      final Interval bounds,
      final List<ActivityInstance> activities,
      final Map<String, LinearProfile> realProfiles,
      final Map<String, DiscreteProfile> discreteProfiles
  ) {
    this.planStart = planStart;
    this.bounds = bounds;
    this.activities = activities;
    this.realProfiles = realProfiles;
    this.discreteProfiles = discreteProfiles;
  }

  public SimulationResults(
      gov.nasa.jpl.aerie.merlin.driver.SimulationResults merlinResults
  ) {
    this.planStart = merlinResults.startTime;
    this.bounds = Interval.between(Duration.ZERO, merlinResults.duration);
    this.activities = new ArrayList<>();
    this.realProfiles = new HashMap<>();
    this.discreteProfiles = new HashMap<>();

    for(final var entry : merlinResults.realProfiles.entrySet()) {
      realProfiles.put(entry.getKey(), LinearProfile.fromSimulatedProfile(entry.getValue().segments()));
    }
    for(final var entry : merlinResults.discreteProfiles.entrySet()) {
      discreteProfiles.put(entry.getKey(), DiscreteProfile.fromSimulatedProfile(entry.getValue().segments()));
    }

    final var simulatedActivities = merlinResults.simulatedActivities;
    for (final var entry : simulatedActivities.entrySet()) {
      final var id = entry.getKey();
      final var activity = entry.getValue();

      final var activityOffset = Duration.of(
          planStart.until(activity.start(), ChronoUnit.MICROS),
          Duration.MICROSECONDS);

      activities.add(new ActivityInstance(
          id.id(),
          activity.type(),
          activity.arguments(),
          Interval.between(activityOffset, activityOffset.plus(activity.duration())),
          activity.directiveId()));
    }
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof final SimulationResults o)) return false;

    return Objects.equals(this.bounds, o.bounds) &&
           Objects.equals(this.activities, o.activities) &&
           Objects.equals(this.realProfiles, o.realProfiles) &&
           Objects.equals(this.discreteProfiles, o.discreteProfiles);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.bounds, this.activities, this.realProfiles, this.discreteProfiles);
  }

  public SimulationResults replaceIds(final Map<ActivityDirectiveId, ActivityDirectiveId> map) {
    return new SimulationResults(
        planStart,
        bounds,
        activities.stream().map($ -> {
          if ($.directiveId().isPresent()) {
            final var id = $.directiveId().get();
            if (map.containsKey(id)) {
              return $.withDirectiveId(map.get(id));
            }
          }
          return $;
        }).toList(),
        realProfiles,
        discreteProfiles
    );
  }
}
