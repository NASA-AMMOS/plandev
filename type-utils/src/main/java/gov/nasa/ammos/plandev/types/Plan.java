package gov.nasa.jpl.aerie.types;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A representation of a grounded plan. Contains the necessary information to be simulated.
 */
public final class Plan {
  // Set-once fields
  private final String name;
  private final MissionModelId missionModelId;
  private final Timestamp startTimestamp;
  private final Timestamp endTimestamp;
  private final Map<ActivityDirectiveId, ActivityDirective> activityDirectives;
  private final Map<String, SerializedValue> configuration;

  // Simulation start and end times can be freely updated
  public Timestamp simulationStartTimestamp;
  public Timestamp simulationEndTimestamp;

  public Plan(
      final String name,
      final MissionModelId missionModelId,
      final Timestamp startTimestamp,
      final Timestamp endTimestamp,
      final Map<ActivityDirectiveId, ActivityDirective> activityDirectives
  ) {
    this(
        name,
        missionModelId,
        startTimestamp,
        endTimestamp,
        activityDirectives,
        null,
        startTimestamp,
        endTimestamp);
  }

  public Plan(
      String name,
      Timestamp startTimestamp,
      Timestamp endTimestamp,
      Map<ActivityDirectiveId, ActivityDirective> activityDirectives,
      Map<String, SerializedValue> simulationConfig
  ) {
    this(
        name,
        null,
        startTimestamp,
        endTimestamp,
        activityDirectives,
        simulationConfig,
        startTimestamp,
        endTimestamp);
  }

  public Plan(
      final String name,
      final MissionModelId missionModelId,
      final Timestamp startTimestamp,
      final Timestamp endTimestamp,
      final Map<ActivityDirectiveId, ActivityDirective> activityDirectives,
      final Map<String, SerializedValue> configuration,
      final Timestamp simulationStartTimestamp,
      final Timestamp simulationEndTimestamp
  ) {
    this.name = name;
    this.missionModelId = missionModelId;
    this.startTimestamp = startTimestamp;
    this.endTimestamp = endTimestamp;
    this.activityDirectives = (activityDirectives != null) ? new HashMap<>(activityDirectives) : new HashMap<>();
    this.configuration = (configuration != null) ? new HashMap<>(configuration) : new HashMap<>();
    this.simulationStartTimestamp = simulationStartTimestamp;
    this.simulationEndTimestamp = simulationEndTimestamp;
  }

  public Plan(final Plan other) {
    this.name = other.name;
    this.missionModelId = other.missionModelId;
    this.startTimestamp = other.startTimestamp;
    this.endTimestamp = other.endTimestamp;
    this.simulationStartTimestamp = other.simulationStartTimestamp;
    this.simulationEndTimestamp = other.simulationEndTimestamp;
    this.activityDirectives = new HashMap<>(other.activityDirectives);
    this.configuration = new HashMap<>(other.configuration);
  }

  /**
   * Get the plan's name.
   */
  public String name() {return name;}

  /**
   * Get the id of the mission model this plan will work with.
   */
  public MissionModelId missionModelId() {return missionModelId;}

  /**
   * Get the start of the plan as an Instant.
   */
  public Instant planStartInstant() {return startTimestamp.toInstant();}

  /**
   * Get the duration of the plan.
   */
  public Duration duration() {
    return Duration.of(startTimestamp.microsUntil(endTimestamp), Duration.MICROSECOND);
  }

  /**
   * Get the map of grounded activity directives in this plan.
   */
  public Map<ActivityDirectiveId, ActivityDirective> activityDirectives() {return activityDirectives;}

  /**
   * Get the requested simulation configuration.
   */
  public Map<String, SerializedValue> simulationConfiguration() {return configuration;}

  /**
   * Get the requested simulation start time as an Instant.
   */
  public Instant simulationStartInstant() {return simulationStartTimestamp.toInstant();}

  /**
   * Get the requested simulation duration.
   */
  public Duration simulationDuration() {
    return Duration.of(simulationStartTimestamp.microsUntil(simulationEndTimestamp), Duration.MICROSECOND);
  }

  /**
   * Get the offset between the start time of the plan and the requested start time of simulation.
   */
  public Duration simulationOffset() {
    return Duration.of(startTimestamp.microsUntil(simulationStartTimestamp), Duration.MICROSECOND);
  }

  @Override
  public boolean equals(final Object object) {
    if (!(object instanceof final Plan other)) {
      return false;
    }

    return
        (Objects.equals(this.name, other.name)
         && Objects.equals(this.missionModelId, other.missionModelId)
         && Objects.equals(this.startTimestamp, other.startTimestamp)
         && Objects.equals(this.endTimestamp, other.endTimestamp)
         && Objects.equals(this.activityDirectives, other.activityDirectives)
         && Objects.equals(this.configuration, other.configuration)
         && Objects.equals(this.simulationStartTimestamp, other.simulationStartTimestamp)
         && Objects.equals(this.simulationEndTimestamp, other.simulationEndTimestamp)
        );
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        name,
        missionModelId,
        startTimestamp,
        endTimestamp,
        activityDirectives,
        configuration,
        simulationStartTimestamp,
        simulationEndTimestamp
    );
  }
}
