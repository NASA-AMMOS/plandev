package gov.nasa.jpl.aerie.merlin.protocol.model;

import gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;

import java.util.List;
import java.util.Map;

/**
 * A type of data accepted as input by a Merlin model.
 *
 * <p> An implementation of this interface provides reflective, at-a-distance access to an abstract type {@code T}
 * accepted by a Merlin model as input. Instances may be constructed from serialized arguments using
 * {@link #instantiate(Map)}, and arguments can be recovered from an instance using {@link #getArguments(T)}. </p>
 *
 * <p> This interface "embeds" the abstract type {@code T} in the domain of {@link SerializedValue}s, in that
 * every instance of {@code T} has a corresponding set of arguments that uniquely determines it. Put differently,
 * calling {@link #instantiate(Map)} on the result of {@link #getArguments(T)} method gives a value equivalent to the
 * original {@link T}. </p>
 *
 * <p> Most uses of this interface, however, will attempt to instantiate a value of type {@link T} from
 * manually-constructed sets of arguments, for the simple reason that clients cannot use {@link #getArguments(T)}
 * without already having a value of type {@link T}. This interface provides several facilities to assist clients in
 * finding instantiable sets of arguments. </p>
 *
 * <ul>
 *   <li> The {@link #getParameters()} method names all (and only) the arguments accepted by {@link #instantiate(Map)}. </li>
 *
 *   <li> The {@link #getParameters()} method provides a rough schema for each argument. An argument that does not meet
 *   this schema is guaranteed to be rejected; an argument that does meet this schema may still be rejected due to
 *   semantic invalidity. (It is hard, for instance, to capture requirements like "sorted list" or "legally-formatted
 *   email address" schematically, and a constructor of a domain type may fail for any similar reason.) </li>
 *
 *   <li> The {@link #getRequiredParameters()} method indicates each parameter for which an argument must be provided.
 *   A required parameter left unspecified is guaranteed to fail instantiation; but a set of arguments that provides all
 *   required parameters may still fail. For instance, if at least one of parameters "x" and "y" must be provided, then
 *   there exist instantiable argument sets where either one is not present, so neither is "required" in all cases. </li>
 *
 *   <li> The {@link #instantiate(Map)} method will throw an {@link IllegalArgumentException} if the argument set is
 *   not instantiable, and will include actionable information about what to do to bring the argument set closer to
 *   validity. Where the other methods speak about all possible values for type {@code T}, this method is concerned with
 *   the values that the given argument set could possible indicate. Errors typically fall into two classes:
 *   under-specification, where more arguments are needed, and over-specification, where conflicts among the arguments
 *   mean that no possible value of type {@code T} observes those arguments. </li>
 * </ul>
 *
 * <p> The {@link #getParameters()} and {@link #getRequiredParameters()} provide primarily schematic information to
 * support user interface customization for editing and displaying the arguments for this type. In turn,
 * {@link #instantiate(Map)} is the final source of truth on what sets of arguments are actually instantiable; the
 * schematic information only sets a low bar to constrain inputs to those that have a chance of being reasonable. </p>
 *
 * <p> The {@link #getValidationFailures(T)} method provides <i>post-instantiation</i> notices, providing additional
 * feedback on values which may be legal yet unadvisable for some reason. These may be thought of as "warnings" (or
 * even weaker, depending on their contents), where failures to instantiate constitute "errors". </p>
 *
 * @param <T>
 *   The abstract type of input described by this object.
 */
public interface InputType<T> {
  /**
   * Gets the ordered family of named parameters defining this type.
   *
   * <p> Each parameter describes the name and schematic approximation of an argument accepted by
   * {@link #instantiate(Map)}. </p>
   *
   * <p> The order of parameters is meaningful: models may list their parameters in an order recommended for human
   * operators to specify them in. For instance, required parameters <i>may</i> be sorted toward the front, and
   * rarely-used parameters <i>may</i> be sorted toward the back. Alternatively, order may be used to group semantically
   * related parameters. </p>
   *
   * <p> Clients should preserve the order of this list whenever it might be displayed for a human
   * operator. </p>
   *
   * <p> Conversely, this order only matters for parameters; {@link #instantiate(Map)} accepts an unordered {@code Map},
   * so the order of specified arguments is irrelevant. </p>
   *
   * @return
   *   An ordered family of named parameters describing the arguments for {@link #instantiate(Map)}.
   */
  List<Parameter> getParameters();

  /**
   * Names a subset of parameters returned by {@link #getParameters()} whose absence on instantiation would
   * certainly result in an {@link InstantiationException}.
   *
   * <p> Providing a schematically-valid argument for every "required" parameter does not guarantee success in
   * instantiating a value of type {@code T}. For example, two parameters may be inter-derivable: if only one is not
   * given, the other could be inferred from it. Neither parameter is "required", because some instantiation could
   * succeed even with that parameter unspecified. </p>
   *
   * <p> The best way to see if a set of arguments describes a valid input value is to attempt to instantiate it with
   * {@link #instantiate(Map)}. </p>
   *
   * @return
   *   A set of parameters whose absence will cause {{@link #instantiate(Map)}} to fail.
   */
  List<String> getRequiredParameters();

  /**
   * Instantiates a value of type {@code T} from arguments.
   *
   * <p> The arguments provided must correspond to the parameters returned by {@link #getParameters()}, and must
   * at a minimum include an argument for each parameter indicated by {@link #getRequiredParameters()}. </p>
   *
   * <p> On the other hand, not every parameter must be supplied an argument. If an argument is left unspecified, it can
   * sometimes be automatically filled in, whether by deriving it from its relationship with other arguments or by
   * automatically inferring a default value. </p>
   *
   * <p> Instantiation may fail for one of two reasons: </p>
   *
   * <ul>
   *   <li> <i>Under-specification</i>: Multiple possible values of type {@code T} match the specified arguments. More
   *   arguments need to be specified in order to pin down a single value of type {@code T} uniquely. </li>
   *
   *   <li> <i>Over-specification</i>: No value of type {@code T} matches the specified arguments. Some of the specified
   *   arguments need to be changed (or removed, if there is no parameter by a specified name). </li>
   * </ul>
   *
   * @param arguments
   *   The arguments determining a value of type {@code T}.
   * @return
   *   The instance of type {@code T} corresponding to the given arguments.
   * @throws InstantiationException
   *   When the given arguments do not uniquely (or at all) determine a value of type {@code T}.
   */
  // TODO: Define distinct exceptions for under-specification and over-specification failures,
  //   and provide actionable information specific to each kind of failure.
  //  * Under-specification: We can usually tell what arguments remain to be specified.
  //  * Over-specification: We can usually tell which specified arguments are in error.
  T instantiate(Map<String, SerializedValue> arguments)
      throws InstantiationException;

  /**
   * Extracts the complete set of arguments for a given value of type {@code T}.
   *
   * <p> The arguments returned by this method are "complete" in the sense that every parameter described by
   * {@link #getParameters()} has a corresponding argument returned by this method. The representation of each argument
   * <i>may</i> be different from that originally provided to {@link #instantiate(Map)}. However, it is guaranteed
   * that invoking {@link #instantiate(Map)} on the arguments returned by this method will produce an equivalent value
   * of type {@code T}. </p>
   *
   * @param value
   *   The value to extract arguments from.
   * @return
   *   The complete set of arguments determining the provided value.
   */
  Map<String, SerializedValue> getArguments(T value);

  /**
   * Provides validation information about a value of type {@code T}.
   *
   * <p> Not all values of {@code T} may be recommended for use with the model. This method provides information
   * for a human operator about the given value, such as recommendations to change arguments to meet higher standards
   * of validity. </p>
   *
   * <p> For instance, supposing {@code T} has two integer arguments, it may be best in most cases for those two
   * arguments to differ by at most 5. Values where the difference is greater may still be used, and their effects on
   * the model observed, but the human operator may need to be cognizant of that deviation earlier. </p>
   *
   * <p> The order of the list of validation notices is meaningful; later messages may build on the information
   * conveyed by earlier messages. Clients should preserve the order of this list whenever it might be displayed for a
   * human operator. </p>
   *
   * @param value
   *   The value to validate.
   * @return
   *   An ordered list of informational notices for a human operator.
   */
  List<ValidationNotice> getValidationFailures(T value);

  /** A named parameter to an {@link InputType} */
  record Parameter(String name, ValueSchema schema) {}

  /** A human-readable advisory concerning a subset of the arguments for an instance of an {@link InputType}. */
  record ValidationNotice(List<String> subjects, String message) { }

  /**
   * Provides validation information about the value of type {@code T} determined by a set of arguments.
   *
   * <p> This method is a convenience method, allowing callers to avoid binding the generic type {@code T}. This method
   * must behave as though implemented as: </p>
   *
   * {@snippet :
   * return this.getValidationFailures(this.instantiate(arguments));
   * }
   *
   * @param arguments
   *   The arguments determining a value of type {@code T}.
   * @return
   *   An ordered list of informational notices for a human operator.
   * @throws InstantiationException
   *   When the given arguments do not uniquely (or at all) determine a value of type {@code T}.
   * @see #instantiate(Map)
   * @see #getValidationFailures(T)
   */
  default List<ValidationNotice> validateArguments(final Map<String, SerializedValue> arguments)
  throws InstantiationException
  {
    return this.getValidationFailures(this.instantiate(arguments));
  }

  /**
   * Completes a set of arguments from the value of type {@code T} it determines.
   *
   * <p> This method is a convenience method, allowing callers to avoid binding the generic type {@code T}. This method
   * must behave as though implemented as: </p>
   *
   * {@snippet :
   * return this.getArguments(this.instantiate(arguments));
   * }
   *
   * @param arguments
   *   The arguments determining a value of type {@code T}.
   * @return
   *   The completed set of arguments determining the same unique value as the given arguments.
   * @throws InstantiationException
   *   When the given arguments do not uniquely (or at all) determine a value of type {@code T}.
   * @see #instantiate(Map)
   * @see #getArguments(T)
   */
  default Map<String, SerializedValue> getEffectiveArguments(final Map<String, SerializedValue> arguments)
  throws InstantiationException
  {
    return this.getArguments(this.instantiate(arguments));
  }
}
