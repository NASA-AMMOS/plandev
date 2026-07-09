package gov.nasa.jpl.aerie.merlin.protocol.driver;

import gov.nasa.jpl.aerie.merlin.protocol.model.CellType;
import gov.nasa.jpl.aerie.merlin.protocol.model.OutputType;
import gov.nasa.jpl.aerie.merlin.protocol.model.Resource;
import gov.nasa.jpl.aerie.merlin.protocol.model.Task;
import gov.nasa.jpl.aerie.merlin.protocol.model.TaskFactory;

import java.util.function.Function;

/**
 * An interface to the driver during model instantiation.
 *
 * <p> An {@code Initializer} allows models to {@linkplain #allocate(Object, CellType, Function, Topic) allocate}
 * storage for simulation-aware state, which is the only mutable state that can safely be shared among
 * stateful cells that can be read and updated from {@link Task}s. The model must not interact with an {@code Initializer}
 * beyond the scope in which it was received. </p>
 *
 * <p> Models can also export resources (time-varying values) and topics (streams of discrete events) via this interface.
 * These observable outputs of the model provide feedback to users, displaying the effect on the model of the input
 * directives and configuration. Observing the behavior of resources and topics is very nearly the whole point of
 * simulating a model, so it is essential to register these outputs for clients to make use of. </p>
 *
 * <p> In a future revision of this API, exportation of resources and topics may be moved to the {@link gov.nasa.jpl.aerie.merlin.protocol.model.ModelType}
 * interface, so that the existence and shape of these outputs cannot vary with model configuration. </p>
 */
public interface Initializer {
  /**
   * Gets the current state of the cell with the given ID.
   *
   * <p> Since cells cannot be mutated during initialization, this method always returns the initial state provided to
   * {@link #allocate(Object, CellType, Function, Topic)} for the given cell ID. </p>
   *
   * @param <State>
   *   The type of state held by the cell.
   * @param cellId
   *   The ID of the cell to query.
   * @return
   *   The current state of the cell.
   */
  <State>
  State getInitialState(CellId<State> cellId);

  /**
   * Allocates a stateful cell with the given initial state, which may evolve over time and under effects. The cell is
   * automatically subscribed to the provided topic, whose events are interpreted as effects by the given function.
   *
   * <p> All mutable state referenced by a model must be allocated via this method. In turn, this method returns
   * {@link gov.nasa.jpl.aerie.merlin.protocol.driver.CellId}s, which identify the cell to the driver in future
   * read/write interactions. This makes it possible to give each concurrent task a transactional view of the model
   * state, to resolve any concurrent effects on state in a coherent way, and to identify which resources need to be
   * recomputed based on when the cells they are computed from are updated. </p>
   *
   * <p> The given {@link CellType} describes how concurrent effects resolve into coherent updates to the cell's state,
   * and how time causes the state to evolve autonomously. The given {@link Topic} and associated interpretation
   * function describe how a particular class of events influences this cell. In the future, it may become possible to
   * subscribe a cell to zero or multiple topics, in which case this method will likely factor into separate actions
   * for allocation and subscription. </p>
   *
   * @param <Event>
   *   The type of event streamed over the given topic.
   * @param <Effect>
   *   The type of effect accepted by the given cell type.
   * @param <State>
   *   The type of state managed by the given cell type.
   * @param initialState
   *   The initial state of the cell.
   * @param cellType
   *   A specification of the cell's behavior over time and under effects.
   * @param interpretation
   *   An interpretation of events as effects on this particular cell.
   * @param topic
   *   A stream of events to subscribe this cell to.
   * @return
   *   A unique token identifying the allocated cell.
   */
  <Event, Effect, State>
  CellId<State> allocate(
      State initialState,
      CellType<Effect, State> cellType,
      Function<Event, Effect> interpretation,
      Topic<Event> topic);

  /**
   * Registers a specification for a top-level "daemon" task to be spawned at the beginning of simulation.
   *
   * <p> Daemon tasks are so-named in analogy to the <a href="https://en.wikipedia.org/wiki/Daemon_(computing)">"daemon"
   * processes</a> of UNIX, which are background processes that monitor system state and take action on some condition
   * or periodic schedule. Merlin's daemon tasks are much the same: tasks that exist on the model's behalf, rather than
   * as reactions to environmental stimuli, which may model some system upkeep behavior on some condition or periodic
   * schedule. </p>
   *
   * <p> The return value from a daemon task is discarded and ignored. </p>
   *
   * @param taskName A name to associate with the task so that it can be rerun
   * @param factory A factory for constructing instances of the daemon task.
   */
  void daemon(final String taskName, TaskFactory<?> factory);

  /**
   * Registers a specification for a top-level "daemon" task to be spawned at the beginning of simulation.
   *
   * <p> Daemon tasks are so-named in analogy to the <a href="https://en.wikipedia.org/wiki/Daemon_(computing)">"daemon"
   * processes</a> of UNIX, which are background processes that monitor system state and take action on some condition
   * or periodic schedule. Merlin's daemon tasks are much the same: tasks that exist on the model's behalf, rather than
   * as reactions to environmental stimuli, which may model some system upkeep behavior on some condition or periodic
   * schedule. </p>
   *
   * <p> The return value from a daemon task is discarded and ignored. </p>
   *
   * @param factory A factory for constructing instances of the daemon task.
   */
  default void daemon(TaskFactory<?> factory) {
    daemon(null, factory);
  }

  /**
   * Registers a model resource whose value over time is observable by the environment.
   *
   * @param name
   *   The name of the resource, unique amongst all resources.
   * @param resource
   *   The method to use to observe the value of the resource.
   */
  void resource(
      String name,
      Resource<?> resource);

  /**
   * Registers a stream of events (a "topic") observable by the environment.
   *
   * @param name
   *   The name of the topic, unique amongst all topics.
   * @param topic
   *   The identifier associated to each event on the topic.
   * @param outputType
   *   A description of the type of data carried by events on the topic.
   * @param <Event>
   *   The type of data carried by events on the topic.
   */
  <Event>
  void topic(
      String name,
      Topic<Event> topic,
      OutputType<Event> outputType);
}
