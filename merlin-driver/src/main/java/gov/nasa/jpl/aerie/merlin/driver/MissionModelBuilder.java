package gov.nasa.jpl.aerie.merlin.driver;

import gov.nasa.jpl.aerie.merlin.driver.engine.EngineCellId;
import gov.nasa.jpl.aerie.merlin.driver.timeline.CausalEventSource;
import gov.nasa.jpl.aerie.merlin.driver.timeline.Cell;
import gov.nasa.jpl.aerie.merlin.driver.timeline.LiveCells;
import gov.nasa.jpl.aerie.merlin.driver.timeline.Query;
import gov.nasa.jpl.aerie.merlin.driver.timeline.RecursiveEventGraphEvaluator;
import gov.nasa.jpl.aerie.merlin.driver.timeline.Selector;
import gov.nasa.jpl.aerie.merlin.protocol.driver.CellId;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Initializer;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Topic;
import gov.nasa.jpl.aerie.merlin.protocol.model.CellType;
import gov.nasa.jpl.aerie.merlin.protocol.model.OutputType;
import gov.nasa.jpl.aerie.merlin.protocol.model.Resource;
import gov.nasa.jpl.aerie.merlin.protocol.model.TaskFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public final class MissionModelBuilder implements Initializer {
  private MissionModelBuilderState state = new UnbuiltState();

  @Override
  public <State> State getInitialState(
      final CellId<State> cellId)
  {
    return this.state.getInitialState(cellId);
  }

  @Override
  public <EventType, Effect, State>
  CellId<State> allocate(
      final State initialState,
      final CellType<Effect, State> cellType,
      final Function<EventType, Effect> interpretation,
      final Topic<EventType> topic
  ) {
    return this.state.allocate(initialState, cellType, interpretation, topic);
  }

  @Override
  public void resource(final String name, final Resource<?> resource) {
    this.state.resource(name, resource);
  }

  @Override
  public <Event> void topic(
      final String name,
      final Topic<Event> topic,
      final OutputType<Event> outputType)
  {
    this.state.topic(name, topic, outputType);
  }

  @Override
  public void daemon(final String taskName, final TaskFactory<?> task) {
    this.state.daemon(taskName, task);
  }

  public <Model>
  MissionModel<Model> build(final Model model, final DirectiveTypeRegistry<Model> registry) {
    return this.state.build(model, registry);
  }

  private interface MissionModelBuilderState extends Initializer {
    <Model> MissionModel<Model>
    build(
        Model model,
        DirectiveTypeRegistry<Model> registry);
  }

  private final class UnbuiltState implements MissionModelBuilderState {
    private final LiveCells initialCells = new LiveCells(new CausalEventSource());

    private final Map<String, Resource<?>> resources = new HashMap<>();
    private final Map<String, TaskFactory<?>> daemons = new HashMap<>();
    //private final List<MissionModel.SerializableTopic<?>> topics = new ArrayList<>();
    private final HashMap<Topic<?>, MissionModel.SerializableTopic<?>> topics = new HashMap<>();

    @Override
    public <State> State getInitialState(
        final CellId<State> token)
    {
      // SAFETY: The only `Query` objects the model should have were returned by `UnbuiltState#allocate`.
      @SuppressWarnings("unchecked")
      final var query = (EngineCellId<?, State>) token;

      final var state$ = this.initialCells.getState(query.query());

      return state$.orElseThrow(IllegalArgumentException::new);
    }

    @Override
    public <EventType, Effect, State>
    CellId<State> allocate(
        final State initialState,
        final CellType<Effect, State> cellType,
        final Function<EventType, Effect> interpretation,
        final Topic<EventType> topic
    ) {
      // TODO: The evaluator should probably be specified later, after the model is built.
      //   To achieve this, we'll need to defer the construction of the initial `LiveCells` until later,
      //   instead simply storing the cell specification provided to us (and its associated `Query` token).
      final var evaluator = new RecursiveEventGraphEvaluator();

      final var query = new Query<State>();
      this.initialCells.put(query, new Cell<>(
          cellType,
          new Selector<>(topic, interpretation),
          evaluator,
          initialState));

      return new EngineCellId<>(topic, query);
    }

    @Override
    public void resource(final String name, final Resource<?> resource) {
      this.resources.put(name, resource);
    }

    @Override
    public <Event> void topic(
        final String name,
        final Topic<Event> topic,
        final OutputType<Event> outputType)
    {
      this.topics.put(topic, new MissionModel.SerializableTopic<>(name, topic, outputType));
    }

    /**
     * Collect daemons to run at the start of simulation.  Record unique names/IDs for daemon
     * tasks such that a simulation rerun can identify them and handle effects properly.
     * If the mission model does not specify a name ({@code taskName == null}), then
     * re-executing the daemon will re-apply any effects, potentially resulting in
     * an inaccurate simulation.  This function will add a suffix if necessary to the passed-in name
     * in order to make it unique. If null is passed, "daemon" is used.  The same IDs
     * will be generated for tasks with passed-in names in consecutive runs so that they
     * can be correlated.
     * @param taskName A name to associate with the task so that it can be rerun
     * @param task A factory for constructing instances of the daemon task.
     */
    @Override
    public void daemon(String taskName, final TaskFactory<?> task) {
      int numDigits = 5;
      int ct = 0;
      taskName = taskName == null ? "daemon" : taskName;
      String id = taskName;
      // If we care how fast this is, we should save the ct for the taskName so that we don't have to visit
      // every daemon with the same name, or we should do a binary search.
      while (true) {
        if (!this.daemons.containsKey(id)) {
          break;
        }
        String suffix = String.format("%0" + numDigits + "d", ct);
        id = taskName + suffix;
        ct++;
        if (ct >= Math.pow(10,numDigits)) {
          throw new RuntimeException("Too many daemon tasks!  Limit is " + ct + ".");
        }
      }
      this.daemons.put(id, task);
    }

    @Override
    public <Model>
    MissionModel<Model> build(final Model model, final DirectiveTypeRegistry<Model> registry) {
      final var missionModel = new MissionModel<>(
          model,
          this.initialCells,
          this.resources,
          this.topics,
          this.daemons,
          registry);

      MissionModelBuilder.this.state = new BuiltState();

      return missionModel;
    }
  }

  private static final class BuiltState implements MissionModelBuilderState {
    @Override
    public <State> State getInitialState(
        final CellId<State> cellId)
    {
      throw new IllegalStateException("Cannot interact with the builder after it is built");
    }

    @Override
    public <EventType, Effect, State>
    CellId<State> allocate(
        final State initialState,
        final CellType<Effect, State> cellType,
        final Function<EventType, Effect> interpretation,
        final Topic<EventType> topic
    ) {
      throw new IllegalStateException("Cells cannot be allocated after the schema is built");
    }

    @Override
    public void resource(final String name, final Resource<?> resource) {
      throw new IllegalStateException("Resources cannot be added after the schema is built");
    }

    @Override
    public <Event> void topic(
        final String name,
        final Topic<Event> topic,
        final OutputType<Event> outputType)
    {
      throw new IllegalStateException("Topics cannot be added after the schema is built");
    }

    @Override
    public void daemon(final String taskName, final TaskFactory<?> task) {
      throw new IllegalStateException("Daemons cannot be added after the schema is built");
    }

    @Override
    public <Model>
    MissionModel<Model> build(final Model model, final DirectiveTypeRegistry<Model> registry) {
      throw new IllegalStateException("Cannot build a builder multiple times");
    }
  }
}
