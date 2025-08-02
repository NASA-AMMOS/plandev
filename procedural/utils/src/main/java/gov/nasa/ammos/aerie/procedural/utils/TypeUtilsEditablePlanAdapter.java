package gov.nasa.ammos.aerie.procedural.utils;

import gov.nasa.ammos.aerie.procedural.scheduling.utils.DefaultEditablePlanDriver;
import gov.nasa.ammos.aerie.procedural.scheduling.utils.PerishableSimulationResults;
import gov.nasa.ammos.aerie.procedural.timeline.Interval;
import gov.nasa.ammos.aerie.procedural.timeline.collections.Directives;
import gov.nasa.ammos.aerie.procedural.timeline.collections.ExternalEvents;
import gov.nasa.ammos.aerie.procedural.timeline.ops.SerialSegmentOps;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.Segment;
import gov.nasa.ammos.aerie.procedural.timeline.plan.EventQuery;
import gov.nasa.ammos.aerie.procedural.timeline.plan.SimulationResults;
import gov.nasa.jpl.aerie.merlin.driver.MissionModel;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.ammos.aerie.procedural.scheduling.plan.EditablePlan;
import gov.nasa.ammos.aerie.procedural.scheduling.simulation.SimulateOptions;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.orchestration.simulation.SimulationUtility;
import gov.nasa.jpl.aerie.scheduler.plan.MerlinToProcedureSimulationResultsAdapter;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.AnyDirective;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.Directive;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.DirectiveStart;
import gov.nasa.jpl.aerie.types.ActivityDirective;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.Plan;
import kotlin.jvm.functions.Function1;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * An {@link EditablePlan} implementation for the type-utils {@link Plan} class.
 * Allows for local simulation and plan editing, such as for custom scheduler drivers,
 * constraint checkers, or other workflows that don't use the Aerie database.
 *
 * To use, create a {@link SimulationUtility} and use that to load the mission model.
 * Then create a {@link Plan} object; you can create one with the constructor or by using
 * {@link gov.nasa.jpl.aerie.orchestration.PlanJsonParser} to load a plan from file.
 * Then you can create an editable plan object like this:
 *
 * ```
 * editablePlan = new DefaultEditablePlanDriver(
 *     new TypeUtilsEditablePlanAdapter(
 *         new TypeUtilsPlanAdapter(myPlan),
 *         simUtility,
 *         model
 *     )
 * );
 * ```
 *
 * You can then edit and simulate the plan using the {@link EditablePlan} interface. You can pass it
 * to a scheduling goal, a constraint, or anything else that needs the plan and sim results. The official
 * constraint interface takes an immutable plan and sim results separately, so you can call a constraint like
 * this: `new MyConstraint(...).run(plan, plan.simulate())`. (The {@link EditablePlan} will be upcast to
 * the immutable {@link gov.nasa.ammos.aerie.procedural.timeline.plan.Plan} interface.
 */
public class TypeUtilsEditablePlanAdapter implements gov.nasa.ammos.aerie.procedural.timeline.plan.Plan,
    DefaultEditablePlanDriver.PlanEditAdapter
{

  private final TypeUtilsPlanAdapter plan;
  private final SimulationUtility simUtility;
  private final MissionModel<?> model;
  private long idCounter;
  private SimulationResults latestResults;

  private boolean changedSinceLastSim = true;

  public TypeUtilsEditablePlanAdapter(
      TypeUtilsPlanAdapter plan,
      SimulationUtility simUtility,
      MissionModel<?> model
  ) {
    this.plan = plan;
    this.simUtility = simUtility;
    this.model = model;

    this.idCounter = plan
        .plan().activityDirectives().keySet()
        .stream().max((l, r) -> Math.toIntExact(r.id() - l.id()))
        .orElseGet(() -> new ActivityDirectiveId(0))
        .id();
  }

  @NotNull
  @Override
  public ActivityDirectiveId generateDirectiveId() {
    return new ActivityDirectiveId(idCounter++);
  }

  @Override
  public void create(@NotNull Directive<AnyDirective> directive) {
    changedSinceLastSim = true;
    plan.plan().activityDirectives().put(directive.id, toTypeUtilsActivity(directive));
  }

  @Override
  public void delete(@NotNull ActivityDirectiveId id) {
    changedSinceLastSim = true;
    plan.plan().activityDirectives().remove(id);
  }

  @Override
  public PerishableSimulationResults latestResults() {
    return new PerishableSimResultsWrapper(latestResults, false);
  }

  @Override
  public void simulate(@NotNull SimulateOptions options) throws ExecutionException, InterruptedException {
    if (changedSinceLastSim || latestResults == null) {
      changedSinceLastSim = false;
      final var sim = simUtility.simulate(model, plan.plan());
      final var result = sim.get();
      latestResults = new MerlinToProcedureSimulationResultsAdapter(result, new TypeUtilsPlanAdapter(new Plan(plan.plan())));
    }
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
            switch (activity.getStart()) {
              case DirectiveStart.Anchor a -> a.getParentId();
              case DirectiveStart.Absolute ignored -> null;
                default -> throw new Error("unreachable");
            },
            switch (activity.getStart()) {
              case DirectiveStart.Anchor a -> a.getAnchorPoint() == DirectiveStart.Anchor.AnchorPoint.Start;
              case DirectiveStart.Absolute a -> true;
              default -> throw new Error("unreachable");
            }
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

  @NotNull
  @Override
  public <V, TL extends SerialSegmentOps<V, TL>> TL resource(
          @NotNull String name,
          @NotNull Function1<? super List<Segment<SerializedValue>>, ? extends TL> deserializer
  ) {
    return plan.resource(name, deserializer);
  }

  @NotNull
  @Override
  public ExternalEvents events(@NotNull EventQuery query) {
    return plan.events(query);
  }
}
