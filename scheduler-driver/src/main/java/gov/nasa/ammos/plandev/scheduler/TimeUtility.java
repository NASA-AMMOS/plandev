package gov.nasa.jpl.aerie.scheduler;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.scheduler.model.PlanningHorizon;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

/**
 * represents an particular single instant on a given timeline
 */
public class TimeUtility {
  /**
   * parses the provided input time string as a day-of-month specification
   *
   * recognizes times of the format 2020-11-30T14:33:12.442
   *
   * assumes time zone UTC
   *
   * @param s IN the string to parse as a year, month, day timestamp
   * @return a new time object representing the time specified by the string
   */
  public static Instant fromDOM(String s) {
    return java.time.Instant.from(formatDOM.parse(s));
  }

  public static Duration fromDOM(String s, PlanningHorizon h){
    return h.toDur(fromDOM(s));
  }

  /**
   * parses the provided input time string as a day-of-month specification
   *
   * recognizes times of the format 2020-11-30T14:33:12.442
   *
   * assumes time zone UTC
   *
   * @param s IN the string to parse as a year, month, day timestamp
   * @return a new time object representing the time specified by the string
   */
  public static Instant fromDOY(String s) {
    return java.time.Instant.from(formatDOY.parse(s));
  }

  public static Duration fromDOY(String s, PlanningHorizon h){
    return h.toDur(fromDOY(s));
  }

  /**
   * the time formatter used to emit seq toolchain friendly time formats
   *
   * produces UTC times in the form 2020-122T14:33:66.221
   */
  private static final DateTimeFormatter formatDOY =
      new DateTimeFormatterBuilder().appendPattern("uuuu-DDD'T'HH:mm:ss")
                                    .appendFraction(ChronoField.MICRO_OF_SECOND, 0, 6, true).toFormatter()
                                    .withZone(ZoneId.of("Z"));


  /**
   * the time formatter used to emit human friendly yearh, month, day format
   *
   * produces UTC times in the form 2020-11-30T14:33:66.221
   */
  private static final DateTimeFormatter formatDOM
      = new DateTimeFormatterBuilder().appendPattern("yyyy-MM-dd'T'HH:mm:ss")
                                      .appendFraction(ChronoField.MICRO_OF_SECOND, 0, 6, true).toFormatter()
                                      .withZone(ZoneId.of("Z"));

  public enum Operator {
    PLUS,
    MINUS,
  }

  public static boolean isPositiveOverflowAdd(Duration t1, Duration t2){
      if(t1.isPositive() && t2.isPositive()){
        return true;
      } else if(t1.isNegative() && t2.isNegative()){
        return false;
    }
    throw new RuntimeException("Should not be overflow");
  }

  public static boolean isPositiveOverflowMinus(Duration t1, Duration t2){
    return t1.isPositive() && t2.isNegative() || t1.isNegative() && t2.isPositive();
  }

  public static Duration performOperation(Operator op, Duration t1, Duration d) {
    switch (op) {
      case PLUS:
        try {
          return t1.plus(d);
        }catch(ArithmeticException e){
          if(isPositiveOverflowAdd(t1,d)) {
            return Duration.MAX_VALUE;
          } else{
            return Duration.MIN_VALUE;
          }
        }
      case MINUS:
        try {
          return t1.minus(d);
        }catch(ArithmeticException e){
          if(isPositiveOverflowMinus(t1,d)) {
            return Duration.MAX_VALUE;
          } else{
            return Duration.MIN_VALUE;
          }
        }
      default:
        throw new IllegalArgumentException("Unknown operation");
    }
  }

}

