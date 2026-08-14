/**
 * Interfaces implemented by the external environment of a mission model.
 *
 * <p> Since a model provides methods to be invoked by a driver, typically the only way for the model to communicate
 * back to the driver is via the values returned from its methods. However, it is often necessary for the model to
 * communicate with the driver without returning immediately. The interfaces in this package are provided as arguments
 * to a model at one time or another, allowing the model to call <i>back</i> to the driver. </p>
 *
 * <p> The exception (which proves the rule) is the {@link gov.nasa.jpl.aerie.merlin.protocol.driver.CellId} type, which
 * has no methods of its own. Instead, it acts as a typed identifier that the model can use to ask the driver for
 * the current state of an allocated cell. (It "proves the rule" because it is used precisely when calling the driver
 * back.) </p>
 *
 * <p> The model can only interact with the driver during three contexts: <i>initialization</i>, <i>execution</i>,
 * and <i>inquisition</i>. </p>
 *
 * <ul>
 *   <li>
 *     <p> During <i>initialization</i>, a model can allocate cells to hold its internal state, spawn top-level "daemon"
 *     tasks, and export resources (time-varying values) and topics (streams of discrete events). It can also query the
 *     current state of its cells, even though they cannot be changed from their initial values during this phase. The
 *     {@link gov.nasa.jpl.aerie.merlin.protocol.driver.Initializer} interface grants access to these actions, and is
 *     typically provided to the {@link gov.nasa.jpl.aerie.merlin.protocol.model.ModelType#instantiate(java.time.Instant, java.lang.Object, gov.nasa.jpl.aerie.merlin.protocol.driver.Initializer)}
 *     method. </p>
 *
 *     <p> In the future, resource and topic exportation may be moved to the {@link gov.nasa.jpl.aerie.merlin.protocol.model.ModelType}
 *     class instead, so that the input and output interface of a model can be known independent of a particular choice
 *     of configuration. </p>
 *   </li>
 *
 *   <li>
 *     <p> During <i>execution</i>, a model can get the current value of a cell, emit events (to influence cells or to be
 *     logged on an exported topic), and spawn additional tasks. The {@link gov.nasa.jpl.aerie.merlin.protocol.driver.Scheduler}
 *     interface grants access to these actions, and is typically provided to the {@link gov.nasa.jpl.aerie.merlin.protocol.model.Task#step(gov.nasa.jpl.aerie.merlin.protocol.driver.Scheduler)}
 *     method.</p>
 *
 *     <p> In the future, models may also be able to allocate state during execution, to facilitate sharing temporary
 *     state between subtasks. </p>
 *   </li>
 *
 *   <li> During <i>inquisition</i>, a model can only get the current value of a cell. The {@link gov.nasa.jpl.aerie.merlin.protocol.driver.Querier}
 *   interface provides this read-only access to the model's state, and is typically provided to the {@link gov.nasa.jpl.aerie.merlin.protocol.model.Resource#getDynamics(gov.nasa.jpl.aerie.merlin.protocol.driver.Querier)}
 *   and {@link gov.nasa.jpl.aerie.merlin.protocol.model.Condition#nextSatisfied(gov.nasa.jpl.aerie.merlin.protocol.driver.Querier, gov.nasa.jpl.aerie.merlin.protocol.types.Duration)}
 *   methods. </li>
 * </ul>
 */
package gov.nasa.jpl.aerie.merlin.protocol.driver;
