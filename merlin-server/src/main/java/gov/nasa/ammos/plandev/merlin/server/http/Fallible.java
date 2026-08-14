package gov.nasa.jpl.aerie.merlin.server.http;

import java.util.Optional;

/**
 * A class representing a fallible operation result.
 *
 * @param <SuccessType> The value type stored on success
 * @param <FailureType> The error type stored on failure
 */
public class Fallible<SuccessType, FailureType> {

  private final SuccessType value;
  private final FailureType failureValue;

  private final String message;
  private final boolean isFailure;

  /**
   * Construct a Fallible in which the operation succeeded.
   *
   * @param value The result value.
   */
  private Fallible(SuccessType value) {
    this.value = value;
    this.failureValue = null;
    this.message = "";
    this.isFailure = false;
  }

  /**
   * Construct a Fallible in which the operation failed.
   *
   * @param error The failure error.
   * @param message The failure message.
   */
  private Fallible(FailureType error, final String message) {
    this.value = null;
    this.failureValue = error;
    this.message = message;
    this.isFailure = true;
  }

  /**
   * Check if the operation was a failure.
   *
   * @return true if the operation was a failure, otherwise false.
   */
  public boolean isFailure() {
    return isFailure;
  }

  /**
   * Get the value
   *
   * @return The value;
   */
  public SuccessType get() {
    return value;
  }

  /**
   * Get the value wrapped in an {@link Optional}.
   *
   * @return An {@link Optional} containing the value, or an empty {@link Optional} if the Fallible is a failure.
   */
  public Optional<SuccessType> getOptional() {
    return Optional.ofNullable(value);
  }

  /**
   * Get the failure message.
   */
  public String getMessage() {
    return message;
  }

  /**
   * Get the failure value.
   */
  public FailureType getFailure() {return failureValue;}

  /**
   * Get the failure wrapped in an {@link Optional}.
   *
   * @return An {@link Optional} containing the failure, or an empty {@link Optional} if the Fallible is a success.
   */
  public Optional<FailureType> getFailureOptional() { return Optional.ofNullable(failureValue); }

  /**
   * Return a Fallible with a successful value.
   *
   * @param value The successful value.
   * @param <SuccessType>   The type of the value.
   * @return A successful Fallible.
   */
  public static <SuccessType, FailureType> Fallible<SuccessType, FailureType> of(SuccessType value) {
    return new Fallible<>(value);
  }

  /**
   * Return a Fallible representing a failure with a value.
   *
   * @param error The value indicating failure.
   * @param <FailureType>   The type of the value.
   * @return A failed Fallible with a value.
   */
  public static <SuccessType, FailureType> Fallible<SuccessType, FailureType> failure(FailureType error) {
    return new Fallible<>(error, "");
  }

  /**
   * Return a Fallible representing a failure with a value and a message.
   *
   * @param error   The value indicating failure.
   * @param message The message associated with the failure.
   * @param <FailureType>>     The type of the value.
   * @return A failed Fallible with a value and a message.
   */
  public static <SuccessType, FailureType> Fallible<SuccessType, FailureType> failure(FailureType error, String message) {
    return new Fallible<>(error, message);
  }
}
