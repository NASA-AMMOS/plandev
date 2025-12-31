package gov.nasa.jpl.aerie.merlin.protocol.model;

import gov.nasa.jpl.aerie.merlin.protocol.driver.Initializer;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A Merlin simulation model.
 *
 * <p> A Merlin model describes an <a href="https://en.wikipedia.org/wiki/Open_system_%28systems_theory%29">open
 * system</a>: a stateful system that evolves over time, with defined modes of interaction with its environment.
 * The inputs to the system are known as "directives"; outputs are "resources"; and the state of the system is captured
 * in "cells". The system's environment -- typically a simulation host like Aerie -- may construct and issue directives
 * by using the {@link DirectiveType}s exposed by a model; resources can be queried with the {@link Resource}s exposed
 * by a model; and cells can be managed and advanced through time using the exposed {@link CellType}s. </p>
 *
 * <p> In Java, each of these interfaces, as well as {@code ModelType} itself, provides reflective, at-a-distance access
 * to an abstract domain type relevant to the model. See {@linkplain gov.nasa.jpl.aerie.merlin.protocol the package
 * documentation} for an overview. </p>
 *
 * <p> A {@code ModelType} actually defines a family of related models, in that different models can be
 * {@linkplain #instantiate(Instant, Config, Initializer) instantiated} with different configuration. In order to work
 * consistently with any instance of the model, <i>all</i> models of the given type must have a consistent set of
 * directive types and resource types. That is, while the behavior of any particular directive or resource may vary with
 * configuration, their mere existence and structural characteristics must be fixed. (The same requirement is not levied
 * on cells, as the concrete state of the model is not observable directly by the environment.) </p>
 *
 * @param <Config>
 *   The type of configuration accepted by this model.
 * @param <Model>
 *   The type of instances of this model.
 */
public interface ModelType<Config, Model> {
  /**
   * Gets the directive types supported by this model.
   *
   * @return
   *   The family of named types of directive supported by this model.
   */
  Map<String, ? extends DirectiveType<Model, ?, ?>> getDirectiveTypes();

  /**
   * Gets the configuration type accepted by this model.
   *
   * @return
   *   The configuration type accepted by this model.
   */
  InputType<Config> getConfigurationType();

  /**
   * Gets the subsystems defined in this model.
   *
   * @return A list of the subsystems defined in this model.
   */
  default List<String> getSubsystems() {
    return Collections.emptyList();
  }

  /**
   * Constructs a model instance with the given configuration, allocating cells from the given initializer.
   *
   * <p> <b>The returned model instance must be immutable.</b> All mutable state must be allocated via the provided
   * initializer. In turn, the initializer returns {@link gov.nasa.jpl.aerie.merlin.protocol.driver.CellId}s, which do
   * not contain the cell state, but rather identify the cell to the driver in future read/write interactions. This
   * makes it possible to give each concurrent task a transactional view of the model state, to resolve any concurrent
   * effects on state in a coherent way, and to identify which resources need to be recomputed based on when the cells
   * they are computed from are updated. </p>
   *
   * <p> The state of the resulting model instance can be evolved by either instantiating directives and running their
   * associated tasks, or by progressing time on the simulation-aware state allocated by the model instance. </p>
   *
   * <p> The model also exports resources and topics via the initializer. Notably, the exported resources and topics
   * must not vary with the configuration. The {@code ModelType} API may be changed in the future to enforce this. </p>
   *
   * @param planStart
   *   The real-time instant against which the behavior of this model will be compared to other temporal data.
   * @param configuration
   *   A configuration to instantiate this model with.
   * @param builder
   *   An allocator to allocate cells with.
   * @return
   *   An instance of this model.
   */
  // TODO: Every model instance should export the same resources and topics, independent of any provided configuration.
  //   Extract resource and topic registration from the `Initializer` to the top-level `ModelType`.
  Model instantiate(Instant planStart, Config configuration, Initializer builder);
}
