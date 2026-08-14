package gov.nasa.jpl.aerie.workspace.server.config;

/**
 * occurs when an interface object is encountered that does not match any of an expected set of sub-types
 */
public class UnexpectedSubtypeError extends Error {
  /**
   * construct new exception when an unknown subtype is encountered
   *
   * @param supertype the interface type that the errant object adhered to
   * @param instance the object instance that failed to match any expected sub type
   * @param <E> the interface type that the errant object adhered to
   */
  public <E> UnexpectedSubtypeError(final Class<E> supertype, final E instance) {
    super("Unexpected subtype %s of type %s".formatted(
        instance.getClass().getCanonicalName(),
        supertype.getCanonicalName()));
  }
}
