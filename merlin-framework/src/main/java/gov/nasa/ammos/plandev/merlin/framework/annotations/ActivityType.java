package gov.nasa.jpl.aerie.merlin.framework.annotations;

import gov.nasa.jpl.aerie.merlin.framework.ActivityMapper;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface ActivityType {
  String value();

  @Retention(RetentionPolicy.CLASS)
  @Target(ElementType.TYPE)
  @interface WithMapper {
    Class<? extends ActivityMapper<?, ?, ?>> value();
  }

  enum Executor { Threaded, Replaying }

  @Retention(RetentionPolicy.CLASS)
  @Target(ElementType.METHOD)
  @interface EffectModel {
    Executor value() default Executor.Threaded;
  }

  /**
   * Apply to the effect model when the activity has a parameter that sets the activity's duration.
   *
   * Apply like the following:
   *
   * <pre>{@code
   * @ActivityType("ControllableDurationActivity")
   * public record ControllableDurationActivity(Duration duration) {
   *
   *   @EffectModel
   *   @ActivityType.ControllableDuration(parameterName = "duration")
   *   public void run(Mission mission) {
   *     delay(duration);
   *   }
   * }
   * }</pre>
   *
   * Keep in mind that it is not enough for the activity duration to be *determined* by the duration parameter.
   * They must be exactly equal as above. If that is not true, use {@link ParametricDuration} instead.
   */
  @Retention(RetentionPolicy.CLASS)
  @Target(ElementType.METHOD)
  @interface ControllableDuration {
    String parameterName();
  }

  /**
   * Use when an activity has a constant statically-known duration.
   *
   * Apply to either a static {@link Duration} field or a static no-arg method
   * that returns {@link Duration}. For correctness, it is recommended that you use the field or method
   * in the effect model to ensure that the duration is what you say it is - the duration is verified
   * by the scheduler, but only after a (potentially expensive) simulation.
   *
   * Apply either like the following on a static field:
   *
   * <pre>{@code
   * @ActivityType("FixedDurationActivity")
   * public record FixedDurationActivity() {
   *   @FixedDuration
   *   public static final Duration DURATION = Duration.HOUR;
   *
   *   @EffectModel
   *   public void run(Mission mission) {
   *     delay(DURATION);
   *   }
   * }
   * }</pre>
   *
   * Or like the following on a static method:
   *
   * <pre>{@code
   * @ActivityType("FixedDurationActivity")
   * public record FixedDurationActivity() {
   *   @FixedDuration
   *   public static Duration duration() {
   *     return Duration.HOUR;
   *   }
   *
   *   @EffectModel
   *   public void run(Mission mission) {
   *     delay(duration());
   *   }
   * }
   * }</pre>
   *
   * This annotation is optional, but it is highly recommended if applicable. The scheduler will assume
   * your activity has an uncontrollable variable duration if no duration annotation is present, which
   * will cause extra simulations in scheduling.
   */
  @Retention(RetentionPolicy.CLASS)
  @Target({ ElementType.FIELD, ElementType.METHOD })
  @interface FixedDuration {}

  /**
   * Use when an activity's duration is indirectly determined only by its arguments.
   *
   * Apply to a getter method that returns this activity's duration. For correctness, it is recommended
   * that you use the getter in the effect model to ensure the duration is what you say it is. Apply like this:
   *
   * <pre>{@code
   * @ActivityType("ParametricDurationActivity")
   * public record ParametricDurationActivity(boolean goFast) {
   *   @ParametricDuration
   *   public Duration duration() {
   *     if (goFast) {
   *       return Duration.MINUTE;
   *     } else {
   *       return Duration.HOUR;
   *     }
   *   }
   *
   *   @EffectModel
   *   public void run(Mission mission) {
   *     // ...
   *     delay(duration);
   *     // ...
   *   }
   * }
   * }</pre>
   *
   * If the duration of the activity is exactly equal to one of the arguments, it is recommended to use the
   * {@link ControllableDuration} annotation instead.
   */
  @Retention(RetentionPolicy.CLASS)
  @Target(ElementType.METHOD)
  @interface ParametricDuration {}

  /**
   * Use when the duration of an activity can be upper-bounded,
   * Apply to either a static {@link Duration} field or a static no-arg method
   * that returns {@link Duration}.
   *
   * This annotation can be used when the activity has an uncontrollable or parametric duration (see @ParametricDuration).
   *
   * Apply either like the following on a static field:
   * <pre>{@code
   * @ActivityType("Activity")
   * public record Activity() {
   *   @MaximumDuration
   *   public static final Duration MAXIMUM_DURATION = Duration.HOUR;
   *
   *   @EffectModel
   *   public void run(Mission mission) {
   *     if(mission.resourceA.equals(true){
   *       delay(MAXIMUM_DURATION)
   *     } else{
   *       delay(Duration.MINUTE);
   *     }
   *   }
   * }
   * }</pre>
   *
   * Or like the following on a static method:
   *
   * <pre>{@code
   * @ActivityType("Activity")
   * public record Activity() {
   *   @MaximumDuration
   *   public static Duration maximumDuration() {
   *     return Duration.HOUR;
   *   }
   *
   *   @EffectModel
   *   public void run(Mission mission) {
   *     if(mission.resourceA.equals(true){
   *       delay(maximumDuration())
   *     } else{
   *       delay(Duration.MINUTE);
   *     }
   *   }
   * }
   * }</pre>
   *
   * This annotation is optional, but it is highly recommended if applicable. This annotation allows to perform less simulations.
   *
   */
  @Retention(RetentionPolicy.CLASS)
  @Target({ ElementType.FIELD, ElementType.METHOD })
    @interface MaximumDuration {}
}
