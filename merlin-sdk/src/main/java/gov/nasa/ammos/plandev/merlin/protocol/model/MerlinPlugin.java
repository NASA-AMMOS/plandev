package gov.nasa.jpl.aerie.merlin.protocol.model;

/**
 * A reflection-friendly service for interacting with a simulation model.
 *
 * <p> Since the {@link ModelType} type has generic type parameters, it cannot be instantiated reflectively
 * without using raw types, which puts the onus on the client to decide what type arguments it should take. However,
 * each implementation of {@link ModelType} is only defined for *one* set of type arguments, which are
 * simply unknown to the client. Instead, the {@code MerlinPlugin} interface provides an extra layer of indirection,
 * providing a method that returns a non-raw type with wildcard type arguments, so that the {@code MerlinPlugin} itself
 * is safe to instantiate reflectively. </p>
 *
 * <p> Implementations should register with the {@link java.util.ServiceLoader} service registry; see that class
 * for details. </p>
 */
public interface MerlinPlugin {
  /**
   * Gets the model type for the mission model associated with this plugin.
   *
   * @return
   *   The model type associated with this plugin.
   */
  ModelType<?, ?> getModelType();
}
