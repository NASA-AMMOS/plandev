package gov.nasa.jpl.aerie.orchestration.scheduling;

import gov.nasa.ammos.aerie.procedural.timeline.Interval;
import gov.nasa.ammos.aerie.procedural.timeline.collections.Directives;
import gov.nasa.ammos.aerie.procedural.timeline.plan.SimulationResults;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.ammos.aerie.procedural.scheduling.plan.Edit;
import gov.nasa.ammos.aerie.procedural.scheduling.plan.EditablePlan;
import gov.nasa.ammos.aerie.procedural.scheduling.plan.NewDirective;
import gov.nasa.ammos.aerie.procedural.scheduling.simulation.SimulateOptions;
import gov.nasa.jpl.aerie.merlin.protocol.types.DurationType;
import gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.scheduler.SchedulingInterruptedException;
import gov.nasa.jpl.aerie.scheduler.plan.MerlinToProcedureSimulationResultsAdapter;
import gov.nasa.jpl.aerie.scheduler.simulation.SimulationFacade;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.AnyDirective;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.Directive;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.DirectiveStart;
import gov.nasa.jpl.aerie.scheduler.DirectiveIdGenerator;
import gov.nasa.jpl.aerie.scheduler.model.*;
import gov.nasa.jpl.aerie.types.ActivityDirective;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import kotlin.jvm.functions.Function1;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;


/**
 * An {@link EditablePlan} implementation for the type-utils {@link gov.nasa.jpl.aerie.types.Plan} class.
 *
 * Intended usage:
 * 1. Create a type-utils {@link gov.nasa.jpl.aerie.types.Plan} object. THIS OBJECT WILL BE MUTATED.
 * 2. Create a {@link TypeUtilsProceduralPlan} object from your {@link gov.nasa.jpl.aerie.types.Plan} object.
 * 3. Create a {@link TypeUtilsEditablePlan}.
 * 4. Call all procedural goals in sequence, providing the editable plan as argument. Only one editable plan is needed.
 * 5. Call `getFinalChanges` to get the diff of new activities, or query the original type-utils {@link gov.nasa.jpl.aerie.types.Plan}
 *    object for the total final state of the plan.
 */
public class TypeUtilsEditablePlan implements EditablePlan {

  private DirectiveIdGenerator idGenerator;
  private TypeUtilsProceduralPlan plan;
  private SimulationFacade simFacade;
  private Function<String, ActivityType> lookupActivityType;

  public TypeUtilsEditablePlan(
      DirectiveIdGenerator idGenerator,
      TypeUtilsProceduralPlan plan,
      SimulationFacade simFacade,
      Function<String, ActivityType> lookupActivityType
  ) {
    this.idGenerator = idGenerator;
    this.plan = plan;
    this.simFacade = simFacade;
    this.lookupActivityType = lookupActivityType;
  }

  private final List<Edit> commitedChanges = new ArrayList<>();
  private List<Edit> uncommitedChanges = new ArrayList<>();

  public List<Edit> getFinalChanges() {
    if (!uncommitedChanges.isEmpty()) {
      throw new IllegalStateException("Some plan changes were left uncommitted after scheduling:\n%s".formatted(uncommitedChanges));
    }
    return commitedChanges;
  }

  @Override
  public SimulationResults latestResults() {
    return simFacade.getLatestSimulationData()
        .map($ -> new MerlinToProcedureSimulationResultsAdapter($.driverResults(), false, plan))
        .orElse(null);
  }

  @NotNull
  @Override
  public ActivityDirectiveId create(NewDirective directive) throws InstantiationException {
    final var id = idGenerator.next();
    final var parent = switch (directive.getStart()) {
      case DirectiveStart.Anchor a -> {
        final var parentList = directives()
            .filter(true, $ -> $.id.equals(a.getParentId()))
            .collect(totalBounds());
        if (parentList.size() != 1)
          throw new Error("Expected one parent activity with id %d, found %d".formatted(a.getParentId().id(), parentList.size()));
        yield parentList.getFirst();
      }
      case DirectiveStart.Absolute ignored -> null;
      default -> throw new Error("unreachable");
    };
    final var resolved = directive.resolve(id, parent);
    uncommitedChanges.add(new Edit.Create(resolved));
    validateArguments(resolved, lookupActivityType);
    plan.plan().activityDirectives().put(id, toTypeUtilsActivity(resolved));
    return id;
  }

  @Override
  public void commit() {
    final var toCommit = uncommitedChanges;
    uncommitedChanges = new ArrayList<>();
    commitedChanges.addAll(toCommit);
  }

  @NotNull
  @Override
  public List<Edit> rollback() {
    final var result = uncommitedChanges;
    uncommitedChanges = new ArrayList<>();
    for (final var edit: result) {
      switch (edit) {
        case Edit.Create c -> plan.plan().activityDirectives().remove(c.getDirective().id);
        default -> throw new Error("unreachable");
      }
    }
    return result;
  }

  @NotNull
  @Override
  public SimulationResults simulate(@NotNull SimulateOptions options)
  throws SimulationFacade.SimulationException, SchedulingInterruptedException
  {
    simFacade.simulateWithResults(getSchedulerPlan(), options.getPause().resolve(plan));
    return Objects.requireNonNull(latestResults());
  }

  private static void validateArguments(Directive<AnyDirective> directive, Function<String, ActivityType> lookupActivityType)
  throws InstantiationException
  {
    lookupActivityType.apply(directive.getType()).getSpecType().getInputType().validateArguments(directive.inner.arguments);
  }

  private static ActivityDirective toTypeUtilsActivity(Directive<AnyDirective> activity) {
    return new ActivityDirective(
        switch (activity.getStart()) {
          case DirectiveStart.Anchor a -> a.getOffset();
          case DirectiveStart.Absolute a -> a.getTime();
          default -> throw new Error("unreachable");
        },
        activity.getType(),
        activity.inner.arguments,
        activity.id,
        switch (activity.getStart()) {
          case DirectiveStart.Anchor a -> a.getAnchorPoint() == DirectiveStart.Anchor.AnchorPoint.Start;
          case DirectiveStart.Absolute a -> true;
          default -> throw new Error("unreachable");
        }
    );
  }

  private Plan getSchedulerPlan() {
    final var result = new PlanInMemory();
    result.add(
        plan.plan().activityDirectives()
            .entrySet()
            .stream()
            .map($ -> {
              try {
                return toSchedulingActivity($.getKey(), $.getValue(), lookupActivityType);
              } catch (InstantiationException e) {
                throw new RuntimeException(e);
              }
            })
            .toList()
    );
    return result;
  }

  private static SchedulingActivity toSchedulingActivity(ActivityDirectiveId id, ActivityDirective activity, Function<String, ActivityType> lookupActivityType)
  throws InstantiationException
  {
    return new SchedulingActivity(
        id,
        lookupActivityType.apply(activity.serializedActivity().getTypeName()),
        activity.startOffset(),
        switch(lookupActivityType.apply(activity.serializedActivity().getTypeName()).getDurationType()) {
          case DurationType.Controllable c -> new Duration(activity.serializedActivity().getArguments().get(c.parameterName()).asInt().get());
          case DurationType.Parametric p -> p.durationFunction().apply(activity.serializedActivity().getArguments());
          case DurationType.Fixed f -> f.duration();
          case DurationType.Uncontrollable ignored -> Duration.ZERO;
        },
        activity.serializedActivity().getArguments(),
        null,
        activity.anchorId(),
        activity.anchoredToStart(),
        false
    );
  }

  // DELEGATED METHODS

  @NotNull
  @Override
  public Interval totalBounds() {
    return plan.totalBounds();
  }

  @NotNull
  @Override
  public Duration toRelative(@NotNull final Instant abs) {
    return plan.toRelative(abs);
  }

  @NotNull
  @Override
  public Instant toAbsolute(@NotNull final Duration rel) {
    return plan.toAbsolute(rel);
  }

  @NotNull
  @Override
  public <A> Directives<A> directives(
      @Nullable final String type,
      @NotNull final Function1<? super SerializedValue, ? extends A> deserializer)
  {
    return plan.directives(type, deserializer);
  }
}
