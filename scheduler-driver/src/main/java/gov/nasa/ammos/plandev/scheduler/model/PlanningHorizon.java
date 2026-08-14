package gov.nasa.jpl.aerie.scheduler.model;

import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.scheduler.NotNull;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class PlanningHorizon{

  private final Instant start;
  private final Instant end;

  private final Interval aerieHorizon;

  public PlanningHorizon(@NotNull Instant start, @NotNull Instant end){
    this.start = start;
    this.end = end;
    aerieHorizon = Interval.betweenClosedOpen(Duration.ZERO , Duration.of(ChronoUnit.MICROS.between(start, end), Duration.MICROSECONDS));
  }

  public Interval getHor(){
    return aerieHorizon;
  }

  public Instant getStartInstant(){
    return start;
  }

  public Instant getEndInstant(){
    return end;
  }

  public boolean contains(Duration time){
    return aerieHorizon.contains(time);
  }

  public Duration getStartAerie(){
    return aerieHorizon.start;
  }

  public Duration getEndAerie(){
    return aerieHorizon.end;
  }

  public Duration getAerieHorizonDuration(){
    return getEndAerie();
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
