/**
 * The interfaces and data types describing the interactions between a mission model and its external environment.
 *
 * <p> This package tree defines the foundational interfaces and data types for interactions between a mission model
 * and the system interacting with it (generically referred to as a "driver"). A particular driver can leverage these
 * interactions for various scenarios, including simulation; but these packages have little to say about how simulation
 * is actually performed. Only, when a simulator needs to ask the model what its effect on the simulation is, it will
 * use the interfaces defined here to make that request. </p>
 *
 * <p> In addition, the {@link gov.nasa.jpl.aerie.merlin.protocol.model.MerlinPlugin} interface provides a
 * {@link java.util.ServiceLoader}-friendly service interface, as it hides the generic type parameters of an underlying
 * {@link gov.nasa.jpl.aerie.merlin.protocol.model.ModelType}. Implementors of the latter should typically also
 * provide the former, for interoperability with plugin-based multi-mission systems such as an Aerie deployment. </p>
 *
 * <p> A typical exchange for simulation will look something like the following. </p>
 *
 * <ul>
 *   <li> Driver obtains a {@link gov.nasa.jpl.aerie.merlin.protocol.model.MerlinPlugin} (e.g. by reflection from a
 *   JAR). </li>
 *
 *   <li> Driver calls {@link gov.nasa.jpl.aerie.merlin.protocol.model.MerlinPlugin#getModelType()} to obtain
 *   the top-level {@link gov.nasa.jpl.aerie.merlin.protocol.model.ModelType}. </li>
 *
 *   <li> Driver calls {@link gov.nasa.jpl.aerie.merlin.protocol.model.ModelType#getConfigurationType()} to obtain
 *   the {@link gov.nasa.jpl.aerie.merlin.protocol.model.InputType} describing the model's configuration type. </li>
 *
 *   <li> Driver calls {@link gov.nasa.jpl.aerie.merlin.protocol.model.InputType#instantiate(java.util.Map)} to
 *   construct a configuration input. </li>
 *
 *   <li>
 *     Driver calls {@link gov.nasa.jpl.aerie.merlin.protocol.model.ModelType#instantiate(java.time.Instant, java.lang.Object, gov.nasa.jpl.aerie.merlin.protocol.driver.Initializer)}
 *     to construct a model instance, providing a {@link gov.nasa.jpl.aerie.merlin.protocol.driver.Initializer}.
 *
 *     <ul>
 *       <li> Model calls {@link gov.nasa.jpl.aerie.merlin.protocol.driver.Initializer#allocate(java.lang.Object, gov.nasa.jpl.aerie.merlin.protocol.model.CellType, java.util.function.Function, gov.nasa.jpl.aerie.merlin.protocol.driver.Topic)}
 *       any number of times to allocate mutable internal state described by a {@link gov.nasa.jpl.aerie.merlin.protocol.model.CellType}
 *       and subscribe it to an internal stream of events. </li>
 *
 *       <li> Model calls {@link gov.nasa.jpl.aerie.merlin.protocol.driver.Initializer#daemon(gov.nasa.jpl.aerie.merlin.protocol.driver.Initializer.TaskFactory)}
 *       any number of times to spawn internal {@link gov.nasa.jpl.aerie.merlin.protocol.model.Task}s independent of any external directive. </li>
 *
 *       <li> Model calls {@link gov.nasa.jpl.aerie.merlin.protocol.driver.Initializer#resource(java.lang.String, gov.nasa.jpl.aerie.merlin.protocol.model.Resource)}
 *       any number of times to register an observable {@link gov.nasa.jpl.aerie.merlin.protocol.model.Resource}. </li>
 *
 *       <li> Model calls {@link gov.nasa.jpl.aerie.merlin.protocol.driver.Initializer#topic(java.lang.String, gov.nasa.jpl.aerie.merlin.protocol.driver.Topic, gov.nasa.jpl.aerie.merlin.protocol.model.OutputType)}
 *       any number of times to register an observable {@link gov.nasa.jpl.aerie.merlin.protocol.driver.Topic}. </li>
 *
 *       <li> Model returns a model instance. </li>
 *     </ul>
 *   </li>
 *
 *   <li> Driver calls {@link gov.nasa.jpl.aerie.merlin.protocol.model.ModelType#getDirectiveTypes()} to obtain the
 *   {@link gov.nasa.jpl.aerie.merlin.protocol.model.DirectiveType}s the model can react to. </li>
 *
 *   <li> For each scheduled directive to simulate, Driver calls {@link gov.nasa.jpl.aerie.merlin.protocol.model.DirectiveType#getInputType()}
 *   and then {@link gov.nasa.jpl.aerie.merlin.protocol.model.DirectiveType#getTaskFactory(java.lang.Object, java.util.Map)} to instantiate
 *   a {@link gov.nasa.jpl.aerie.merlin.protocol.model.Task} modeling the model's reaction to the directive. </li>
 *
 *   <li>
 *     Whenever a task needs to be advanced, Driver calls {@link gov.nasa.jpl.aerie.merlin.protocol.model.Task#step(gov.nasa.jpl.aerie.merlin.protocol.driver.Scheduler)},
 *     providing a {@link gov.nasa.jpl.aerie.merlin.protocol.driver.Scheduler}.
 *
 *     <ul>
 *       <li> Model calls {@link gov.nasa.jpl.aerie.merlin.protocol.driver.Scheduler#emit(java.lang.Object, gov.nasa.jpl.aerie.merlin.protocol.driver.Topic)}
 *       any number of times to record an event on some topic. </li>
 *
 *       <li>
 *         Model calls {@link gov.nasa.jpl.aerie.merlin.protocol.driver.Scheduler#get(gov.nasa.jpl.aerie.merlin.protocol.driver.CellId)}
 *         any number of times to get the current value for an allocated stateful cell.
 *
 *         <ul>
 *           <li> Driver converts all events not yet consumed by the cell to per-cell effects by using the topic and projection
 *           registered with the cell. </li>
 *
 *           <li> Driver calls {@link gov.nasa.jpl.aerie.merlin.protocol.model.CellType#getEffectType()} and uses the
 *           returned {@link gov.nasa.jpl.aerie.merlin.protocol.model.EffectTrait} to combine concurrent effects. </li>
 *
 *           <li> Driver calls {@link gov.nasa.jpl.aerie.merlin.protocol.model.CellType#step(java.lang.Object, gov.nasa.jpl.aerie.merlin.protocol.types.Duration)}
 *           and {@link gov.nasa.jpl.aerie.merlin.protocol.model.CellType#apply(java.lang.Object, java.lang.Object)}
 *           to bring the cell's state up to the time at the Model's request. </li>
 *
 *           <li> Driver returns the current state of the cell. </li>
 *         </ul>
 *       </li>
 *
 *       <li> Model calls {@link gov.nasa.jpl.aerie.merlin.protocol.driver.Scheduler#spawn(InSpan taskSpan, TaskFactory task)}
 *       any number of times to spawn additional concurrent {@link gov.nasa.jpl.aerie.merlin.protocol.model.Task}s. </li>
 *
 *       <li> Model returns a {@link gov.nasa.jpl.aerie.merlin.protocol.types.TaskStatus} describing the conditions
 *       under which to resume the task, or produces a terminal output if the task is complete. </li>
 *     </ul>
 *   </li>
 *
 *   <li>
 *     Whenever the Driver wants to observe the current value of a {@link gov.nasa.jpl.aerie.merlin.protocol.model.Resource},
 *     Driver calls {@link gov.nasa.jpl.aerie.merlin.protocol.model.Resource#getDynamics(gov.nasa.jpl.aerie.merlin.protocol.driver.Querier)},
 *     providing a {@link gov.nasa.jpl.aerie.merlin.protocol.driver.Querier}.
 *
 *     <ul>
 *       <li> Model calls {@link gov.nasa.jpl.aerie.merlin.protocol.driver.Querier#getState(gov.nasa.jpl.aerie.merlin.protocol.driver.CellId)}
 *       any number of times to get the current value for an allocated stateful cell. (The Driver responds as in the
 *       analogous case when a {@link gov.nasa.jpl.aerie.merlin.protocol.model.Task} queries a cell.) </li>
 *
 *       <li> Model returns the current observable value of the resource. </li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * @see gov.nasa.jpl.aerie.merlin.protocol.driver
 * @see gov.nasa.jpl.aerie.merlin.protocol.model
 */
package gov.nasa.jpl.aerie.merlin.protocol;

import gov.nasa.jpl.aerie.merlin.protocol.model.TaskFactory;
import gov.nasa.jpl.aerie.merlin.protocol.types.InSpan;
