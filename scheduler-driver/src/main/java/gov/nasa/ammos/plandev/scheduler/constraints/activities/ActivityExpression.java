package gov.nasa.jpl.aerie.scheduler.constraints.activities;

import gov.nasa.jpl.aerie.constraints.model.ActivityInstance;
import gov.nasa.jpl.aerie.constraints.model.DiscreteProfile;
import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.Profile;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.time.Spans;
import gov.nasa.jpl.aerie.constraints.tree.DiscreteProfileFromDuration;
import gov.nasa.jpl.aerie.constraints.tree.DiscreteValue;
import gov.nasa.jpl.aerie.constraints.tree.DurationLiteral;
import gov.nasa.jpl.aerie.constraints.tree.Expression;
import gov.nasa.jpl.aerie.constraints.tree.ProfileExpression;
import gov.nasa.jpl.aerie.merlin.protocol.model.SchedulerModel;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.DurationType;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.scheduler.model.Plan;
import gov.nasa.jpl.aerie.scheduler.model.PlanningHorizon;
import gov.nasa.jpl.aerie.scheduler.model.SchedulingActivity;
import gov.nasa.jpl.aerie.scheduler.model.ActivityType;
import gov.nasa.jpl.aerie.scheduler.NotNull;
import gov.nasa.jpl.aerie.scheduler.Nullable;
import gov.nasa.jpl.aerie.scheduler.solver.stn.TaskNetworkAdapter;
import org.apache.commons.lang3.tuple.Pair;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.ZERO;

/**
 * the criteria used to identify activity instances in scheduling goals
 *
 * the template is a partial specification of an activity instance that
 * can be used to identify candidate activity instances in the plan. it
 * amounts to matching predicate or simple database record query
 *
 * the template can be used by scheduling goals to identify both activities
 * that serve to satisfy the goal as well as other activities that trigger
 * some other conditions in the goal.
 *
 * for example "an image activity of at least 10s duration taken with the
 * green filter" or "every orbit trim maneuver after JOI"
 *
 * templates may be fluently constructed via builders that parse like first
 * order logic predicate clauses, used in building up scheduling rules
 * @param startRange Range of allowed values for matching activity scheduled start times. Activities with null start time do not match any non-null range
 * The range itself determines if endpoints are inclusive or exclusive.
 * @param endRange Range of allowed values for matching activity scheduled end times. Activities with null start time do not match any non-null range.
 * @param durationRange Range of allowed values for duration of matching activities.
 * @param type activity type
 * @param nameRe regular expression of matching activity instance names
 * @param arguments arguments of matching activities.
 */
public record ActivityExpression(
    Interval startRange,
    Interval endRange,
    Pair<Expression<? extends Profile<?>>, Expression<? extends Profile<?>>> durationRange,
    ActivityType type,
    Pattern nameRe,
    Map<String, ProfileExpression<?>> arguments
) implements Expression<Spans> {


  /**
   * a fluent builder class for constructing consistent template queries
   *
   * using the builder is intended to read like a predicate logic clause
   *
   * each different term added to the builder via method calls become part of
   * a logical conjection, ie matching activities must meet all of the
   * specified criteria
   *
   * existing terms can be replaced by calling the same method again, ie
   * matching activities must only meet the last-specified term
   *
   * the builder checks for consistency among all specified terms at least by
   * the final build() call
   *
   */
  public static class Builder {
    protected Duration acceptableAbsoluteTimingError = Duration.of(0, Duration.MILLISECOND);
    Map<String, ProfileExpression<?>> arguments = new HashMap<>();
    protected @Nullable ActivityType type;
    protected @Nullable Interval startsIn;
    protected @Nullable Interval endsIn;
    protected @Nullable Pair<Expression<? extends Profile<?>>, Expression<? extends Profile<?>>> durationIn;
    protected Pattern nameRe;

    public Builder withArgument(String argument, SerializedValue val) {
      arguments.put(argument, new ProfileExpression<>(new DiscreteValue(val)));
      return getThis();
    }

    public Builder withArgument(String argument, ProfileExpression<?> val) {
      arguments.put(argument, val);
      return getThis();
    }

    public Builder withArguments(Map<String, ProfileExpression<?>> arguments){
      arguments.forEach((name, value) -> withArgument(name, value));
      return getThis();
    }

    public Builder withTimingPrecision(Duration acceptableAbsoluteTimingError){
      this.acceptableAbsoluteTimingError = acceptableAbsoluteTimingError;
      return getThis();
    }

    /**
     * requires activities be of a specific activity type
     *
     * the matching instance is allowed to have a type that is derived from
     * the requested type, akin to java instanceof semantics
     *
     * @param type IN STORED required activity type for matching instances,
     *     which should not change while the template exists, or null
     *     if no specific type is required
     * @return the same builder object updated with new criteria
     */
    public @NotNull Builder ofType(@Nullable ActivityType type) {
      this.type = type;
      return getThis();
    }

    /**
     * requires activities have a scheduled start time in a specified range
     *
     * activities without a concrete scheduled start time will not match
     *
     * @param range IN STORED the range of allowed values for start time, or
     *     null if no specific start time is required. should not change
     *     while the template exists. the range itself determines if
     *     inclusive or exclusive at its end points
     * @return the same builder object updated with new criteria
     */
    public @NotNull Builder startsIn(@Nullable Interval range) {
      this.startsIn = extendUpToAbsoluteError(range, acceptableAbsoluteTimingError);
      return getThis();
    }

    /**
     * requires activities have a scheduled end time in a specified range
     *
     * activities without a concrete scheduled start time will not match
     *
     * @param range IN STORED the range of allowed values for start time, or
     *     null if no specific start time is required. should not change
     *     while the template exists. the range itself determines if
     *     inclusive or exclusive at its end points
     * @return the same builder object updated with new criteria
     */
    public @NotNull Builder endsIn(@Nullable Interval range) {
      this.endsIn = extendUpToAbsoluteError(range, acceptableAbsoluteTimingError);
      return getThis();
    }

    /**
     * requires activities have a simulated duration in a specified value
     *
     * activities without a concrete simulated duration will not match
     *
     * @param duration IN STORED the allowed duration, or
     *     null if no specific duration is required. should not change
     *     while the template exists. the range itself determines if
     *     inclusive or exclusive at its end points
     * @return the same builder object updated with new criteria
     */
    public @NotNull Builder durationIn(@Nullable Duration duration) {
      if (duration == null) {
        this.durationIn = Pair.of(Expression.of(() -> new DiscreteProfile()), Expression.of(() -> new DiscreteProfile()));
      }
      this.durationIn = Pair.of(new DiscreteProfileFromDuration(new DurationLiteral(duration)), new DiscreteProfileFromDuration(new DurationLiteral(duration)));
      return getThis();
    }

    /**
     * requires activities have a simulated duration at a specified value
     *
     * activities without a concrete simulated duration will not match
     *
     * @param durationExpression IN STORED the allowed duration.
     * @return the same builder object updated with new criteria
     */
    public @NotNull Builder durationIn(Expression<? extends Profile<?>> durationExpression) {
      this.durationIn = Pair.of(durationExpression, durationExpression);
      return getThis();
    }

    /**
     * bootstraps a new query builder based on an existing activity instance
     *
     * the new builder may then be modified without impacting the existing
     * activity instance, eg by adding additional new terms or replacing
     * existing terms
     *
     * @param existingAct IN the activity instance that serves as the
     *     prototype for the new search criteria. must not be null.
     * @return the same builder object updated with new criteria
     */
    public @NotNull Builder basedOn(@NotNull SchedulingActivity existingAct) {
      type = existingAct.getType();

      if (existingAct.startOffset() != null) {
        this.startsIn(Interval.at(existingAct.startOffset()));
      }

      durationIn(existingAct.duration());

      return getThis();
    }

    private Interval extendUpToAbsoluteError(final Interval interval, final Duration absoluteError){
      final var diff = absoluteError.times(2).minus(interval.duration());
      if(diff.isPositive()){
        final var toApply = diff.dividedBy(2);
        return Interval.between(interval.start.minus(toApply), interval.startInclusivity, interval.end.plus(toApply), interval.endInclusivity);
      } else {
        return interval;
      }
    }

    /**
     * {@inheritDoc}
     */
    public @NotNull
    Builder getThis() {
      return this;
    }

    public @NotNull
    Builder basedOn(@NotNull ActivityExpression template) {
      type = template.type;
      startsIn = template.startRange;
      endsIn = template.endRange;
      durationIn = template.durationRange;
      arguments = template.arguments;
      return getThis();
    }

    /**
     * collect and cross-check all specified terms and construct the template
     *
     * creates a new template object based on a conjunction of all of the
     * criteria specified so far in this builder
     *
     * multiple specifications of the same term sequentially overwrite the
     * prior term specification
     *
     * the terms are checked for high level self-consistency, but it is still
     * possible to construct predicates that will never match any activities
     *
     * @return a newly constructed template that matches activities meeting
     *     the conjunction of all criteria specified to the builder
     */
    public @NotNull ActivityExpression build() {
      return new ActivityExpression(
          startsIn,
          endsIn,
          durationIn,
          type,
          nameRe,
          arguments
      );
    }
  }

  /**
   * creates a template matching a given activity type (or its subtypes)
   *
   * shorthand factory method used in the common case of constraining
   * activities by their type, equievelent to new ActivityTyemplate.
   * Builder().ofType(t).build().
   *
   * @param type IN STORED the required activity type for matching activities.
   *     not null.
   * @return an activity template that matches only activities with the
   *     specified super type
   */
  public static @NotNull ActivityExpression ofType(@NotNull ActivityType type) {
    return new Builder().ofType(type).build();
  }

  /**
   * determines if the given activity matches all criteria of this template
   *
   * if no criteria have been specified, any activity matches the template
   *
   * @param act IN the activity to evaluate against the template criteria.
   *     not null.
   * @return true iff the given activity meets all of the criteria specified
   *     by this template, or false if it does not meet one or more of
   *     the template criteria
   */
  public boolean matches(
      final @NotNull SchedulingActivity act,
      final SimulationResults simulationResults,
      final EvaluationEnvironment evaluationEnvironment,
      final boolean matchArgumentsExactly) {
    final var activityInstance = new ActivityInstance(-1, act.type().getName(), act.arguments(), Interval.between(act.startOffset(), act.getEndTime()));
    return matches(activityInstance, simulationResults, evaluationEnvironment, matchArgumentsExactly);
  }

  public boolean matches(
      final @NotNull ActivityInstance act,
      final SimulationResults simulationResults,
      final EvaluationEnvironment evaluationEnvironment,
      final boolean matchArgumentsExactly) {
    boolean match = (type == null || type.getName().equals(act.type()));

    if (match && startRange != null) {
      final var startT = act.interval().start;
      match = (startT != null) && startRange.contains(startT);
    }

    if (match && endRange != null) {
      final var endT = act.interval().end;
      match = (endT != null) && endRange.contains(endT);
    }

    if (match && durationRange != null) {
      final var dur = act.interval().duration();
      final Optional<Duration> durRequirementLower = this.durationRange.getLeft()
          .evaluate(simulationResults, evaluationEnvironment)
          .valueAt(ZERO)
          .flatMap($ -> $.asInt().map(i -> Duration.of(i, Duration.MICROSECOND)));
      final Optional<Duration> durRequirementUpper = this.durationRange.getRight()
          .evaluate(simulationResults, evaluationEnvironment)
          .valueAt(ZERO)
          .flatMap($ -> $.asInt().map(i -> Duration.of(i, Duration.MICROSECOND)));
      if(durRequirementLower.isEmpty() && durRequirementUpper.isEmpty()){
        throw new RuntimeException("ActivityExpression is malformed, duration bounds are absent but the range is not null");
      }
      if(durRequirementLower.isPresent()){
        match = dur.noShorterThan(durRequirementLower.get());
      }
      if(durRequirementUpper.isPresent()){
        match = match && dur.noLongerThan(durRequirementUpper.get());
      }
    }

    //activity must have all instantiated arguments of template to be compatible
    if (match && arguments != null) {
      Map<String, SerializedValue> actInstanceArguments = act.parameters();
      final var instantiatedArguments = SchedulingActivity
          .instantiateArguments(arguments, act.interval().start, simulationResults, evaluationEnvironment, type);
      if(matchArgumentsExactly){
        for (var param : instantiatedArguments.entrySet()) {
          if (actInstanceArguments.containsKey(param.getKey())) {
            match = actInstanceArguments.get(param.getKey()).equals(param.getValue());
          }
          if (!match) {
            break;
          }
        }
      } else {
        match = subsetOrEqual(SerializedValue.of(actInstanceArguments), SerializedValue.of(instantiatedArguments));
      }
    }
    return match;
  }

  public boolean matches(
      final @NotNull SchedulingActivity act,
      final SimulationResults simulationResults,
      final EvaluationEnvironment evaluationEnvironment,
      final boolean matchArgumentsExactly,
      final Plan plan) {
    boolean match = (type == null || type.getName().equals(act.getType().getName()));

    if (match && startRange != null) {
      final var startT = plan.calculateAbsoluteStartOffsetAnchoredActivity(act);
      match = (startT != null) && startRange.contains(startT);
    }

    if (match && endRange != null) {
      final var endT = act.getEndTime();
      match = (endT != null) && endRange.contains(endT);
    }

    if (match && durationRange != null) {
      final var dur = act.duration();
      final Optional<Duration> durRequirementLower = this.durationRange.getLeft()
                                                                       .evaluate(simulationResults, evaluationEnvironment)
                                                                       .valueAt(Duration.ZERO)
                                                                       .flatMap($ -> $.asInt().map(i -> Duration.of(i, Duration.MICROSECOND)));
      final Optional<Duration> durRequirementUpper = this.durationRange.getRight()
                                                                       .evaluate(simulationResults, evaluationEnvironment)
                                                                       .valueAt(Duration.ZERO)
                                                                       .flatMap($ -> $.asInt().map(i -> Duration.of(i, Duration.MICROSECOND)));
      if(durRequirementLower.isEmpty() && durRequirementUpper.isEmpty()){
        throw new RuntimeException("ActivityExpression is malformed, duration bounds are absent but the range is not null");
      }
      if(durRequirementLower.isPresent()){
        match = dur.noShorterThan(durRequirementLower.get());
      }
      if(durRequirementUpper.isPresent()){
        match = match && dur.noLongerThan(durRequirementUpper.get());
      }
    }

    //activity must have all instantiated arguments of template to be compatible
    if (match && arguments != null) {
      Map<String, SerializedValue> actInstanceArguments = act.arguments();
      final var instantiatedArguments = SchedulingActivity
          .instantiateArguments(arguments, act.startOffset(), simulationResults, evaluationEnvironment, type);
      if(matchArgumentsExactly){
        for (var param : instantiatedArguments.entrySet()) {
          if (actInstanceArguments.containsKey(param.getKey())) {
            match = actInstanceArguments.get(param.getKey()).equals(param.getValue());
          }
          if (!match) {
            break;
          }
        }
      } else {
        match = subsetOrEqual(SerializedValue.of(actInstanceArguments), SerializedValue.of(instantiatedArguments));
      }
    }
    return match;
  }

  @Override
  public Spans evaluate(
      final SimulationResults results,
      final Interval bounds,
      final EvaluationEnvironment environment)
  {
    final var spans = new Spans();
    results.activities.stream().filter(x -> matches(x, results, environment, false)).forEach(x -> spans.add(x.interval()));
    return spans;
  }

  @Override
  public String prettyPrint(final String prefix) {
    return String.format(
        "\n%s(look-for-activity %s)",
        prefix,
        this.type
    );  }

  @Override
  public void extractResources(final Set<String> names) {
    if(this.durationRange != null) {
      this.durationRange.getLeft().extractResources(names);
      this.durationRange.getRight().extractResources(names);
    }
    this.arguments.forEach((name, pe)-> pe.extractResources(names));
  }

  public Optional<Interval> instantiateDurationInterval(
      final PlanningHorizon planningHorizon,
      final EvaluationEnvironment evaluationEnvironment
      ){
    if(durationRange == null) return Optional.empty();
    Optional<Duration> durRequirementLower = Optional.empty();
    Optional<Duration> durRequirementUpper = Optional.empty();
    try {
      durRequirementLower = durationRange().getLeft()
                                                     .evaluate(null, planningHorizon.getHor(), evaluationEnvironment)
                                                     .valueAt(ZERO)
                                                     .flatMap($ -> $.asInt().map(i -> Duration.of(i, Duration.MICROSECOND)));
      durRequirementUpper = durationRange().getRight()
                                                     .evaluate(null, planningHorizon.getHor(), evaluationEnvironment)
                                                     .valueAt(ZERO)
                                                     .flatMap($ -> $.asInt().map(i -> Duration.of(i, Duration.MICROSECOND)));
    } catch (NullPointerException e) {
      throw new UnsupportedOperationException("Activity creation duration arguments cannot depend on simulation results.", e);
    }
    if(durRequirementLower.isPresent() && durRequirementUpper.isPresent()) {
      return Optional.of(Interval.between(durRequirementLower.get(), durRequirementUpper.get()));
    }
    return Optional.empty();
  }

  public Optional<TaskNetworkAdapter.TNActData> reduceTemporalConstraints(
      final PlanningHorizon planningHorizon,
      final SchedulerModel schedulerModel,
      final EvaluationEnvironment evaluationEnvironment,
      final List<Interval> enveloppes){

    var maximumDuration = Duration.MAX_VALUE;
    final var activityTypeMaximumDuration = schedulerModel.getMaximumDurations().get(this.type().getName());
    if(activityTypeMaximumDuration != null){
      maximumDuration = Duration.min(maximumDuration, activityTypeMaximumDuration);
    }

    final var durationType = schedulerModel.getDurationTypes().get(this.type.getName());
    if(durationType instanceof DurationType.Fixed fixed){
      maximumDuration = Duration.min(maximumDuration, fixed.duration());
    }

    var instantiateDurationInterval = this.instantiateDurationInterval(planningHorizon, evaluationEnvironment);
    var minimumDuration = ZERO;
    if(instantiateDurationInterval.isPresent()){
      minimumDuration = Duration.max(minimumDuration, instantiateDurationInterval.get().start);
      maximumDuration = Duration.min(maximumDuration, instantiateDurationInterval.get().end);
    }

    final var durationInterval = Interval.between(minimumDuration, maximumDuration);

    final var allEnveloppes = new ArrayList<Interval>(enveloppes);
    allEnveloppes.add(planningHorizon.getHor());
    return TaskNetworkAdapter.reduceActivityTemporalConstraints(
        startRange(),
        endRange(),
        durationInterval,
        allEnveloppes);
    }

  /**
   * Evaluates whether a SerializedValue can be qualified as the subset of another SerializedValue or not
   * @param superset the proposed superset
   * @param subset the proposed subset
   * @return true if subset is a subset of superset
   */
  public static boolean subsetOrEqual(SerializedValue superset, SerializedValue subset){
    Objects.requireNonNull(superset);
    Objects.requireNonNull(subset);
    final var visitor = new SerializedValue.Visitor<Boolean>(){
      @Override
      public Boolean onNull() {
        return true;
      }

      @Override
      public Boolean onNumeric(final BigDecimal value) {
        final var argumentsAsNumeric = superset.asNumeric();
        return argumentsAsNumeric.map(bigDecimal -> bigDecimal.equals(value)).orElse(false);
      }

      @Override
      public Boolean onBoolean(final boolean value) {
        final var argumentsAsBoolean = superset.asBoolean();
        return argumentsAsBoolean.map(boolValue -> boolValue.equals(value)).orElse(false);
      }

      @Override
      public Boolean onString(final String value) {
        final var argumentsAsString = superset.asString();
        return argumentsAsString.map(stringValue -> stringValue.equals(value)).orElse(false);
      }

      @Override
      public Boolean onMap(final Map<String, SerializedValue> value) {
        final var argumentsAsMap = superset.asMap();
        if(argumentsAsMap.isEmpty()){
          return false;
        }
        for(final var elementInPattern: value.entrySet()){
          final var elementInArguments = argumentsAsMap.get().get(elementInPattern.getKey());
          if(elementInArguments != null){
            if(!subsetOrEqual(elementInArguments, elementInPattern.getValue())){
              return false;
            }
          } else {
            return false;
          }
        }
        return true;
      }

      @Override
      public Boolean onList(final List<SerializedValue> value) {
        final var argumentsAsListOptional = superset.asList();
        if(argumentsAsListOptional.isEmpty()){
          return false;
        }
        if(argumentsAsListOptional.get().size() < value.size()){
          return false;
        }
        for(int i = 0; i < value.size(); i++){
          if(!subsetOrEqual(argumentsAsListOptional.get().get(i), value.get(i))){
            return false;
          }
        }
        return true;
      }
    };
    return subset.match(visitor);
  }
}
