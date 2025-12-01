package gov.nasa.jpl.plandev.scheduler.model;

import gov.nasa.jpl.plandev.constraints.time.Interval;
import gov.nasa.jpl.plandev.merlin.protocol.types.Duration;
import gov.nasa.jpl.plandev.scheduler.NotNull;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class PlanningHorizon{

  private final Instant start;
  private final Instant end;

  private final Interval plandevHorizon;

  public PlanningHorizon(@NotNull Instant start, @NotNull Instant end){
    this.start = start;
    this.end = end;
    plandevHorizon = Interval.betweenClosedOpen(Duration.ZERO , Duration.of(ChronoUnit.MICROS.between(start, end), Duration.MICROSECONDS));
  }

  public Interval getHor(){
    return plandevHorizon;
  }

  public Instant getStartInstant(){
    return start;
  }

  public Instant getEndInstant(){
    return end;
  }

  public boolean contains(Duration time){
    return plandevHorizon.contains(time);
  }

  public Duration getStartPlanDev(){
    return plandevHorizon.start;
  }

  public Duration getEndPlanDev(){
    return plandevHorizon.end;
  }

  public Duration getPlanDevHorizonDuration(){
    return getEndPlanDev();
  }

  public Duration toDur(Instant t){
    return Duration.of(ChronoUnit.MICROS.between(start, t), Duration.MICROSECONDS);
  }

  public Duration fromStart(java.time.Duration duration){
    return toDur(start.plus(duration));
  }

  public Duration fromStart(String duration){
    return fromStart(java.time.Duration.parse(duration));
  }
}
