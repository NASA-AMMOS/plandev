package gov.nasa.jpl.aerie.merlin.driver.retracing;

import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;

import java.util.Map;
import java.util.Objects;

import static java.util.Collections.unmodifiableMap;

/**
 * A serializable representation of a mission model-specific activity domain object.
 *
 * A SerializedActivity is an mission model-agnostic representation of the data in an activity,
 * structured as serializable primitives composed using sequences and maps.
 *
 * For instance, if a FooActivity accepts two parameters, each of which is a 3D point in
 * space, then the serialized activity may look something like:
 *
 *     { "name": "Foo", "parameters": { "source": [1, 2, 3], "target": [4, 5, 6] } }
 *
 * This allows mission-agnostic treatment of activity data for persistence, editing, and
 * inspection, while allowing mission-specific mission model to work with a domain-relevant
 * object via (de)serialization.
 */
public final class SerializedActivity {
  private final String typeName;
  private final Map<String, SerializedValue> arguments;

  public SerializedActivity(final String typeName, final Map<String, SerializedValue> arguments) {
    this.typeName = Objects.requireNonNull(typeName);
    this.arguments = Objects.requireNonNull(arguments);
  }

  /**
   * Gets the name of the activity type associated with this serialized data.
   *
   * @return A string identifying the activity type this object may be deserialized with.
   */
  public String getTypeName() {
    return this.typeName;
  }

  /**
   * Gets the serialized parameters associated with this serialized activity.
   *
   * @return A map of serialized parameters keyed by parameter name.
   */
  public Map<String, SerializedValue> getArguments() {
    return unmodifiableMap(this.arguments);
  }

  // SAFETY: If equals is overridden, then hashCode must also be overridden.
  @Override
  public boolean equals(final Object o) {
    if (!(o instanceof SerializedActivity)) return false;

    final SerializedActivity other = (SerializedActivity)o;
    return
        (  Objects.equals(this.typeName, other.typeName)
        && Objects.equals(this.arguments, other.arguments)
        );
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.typeName, this.arguments);
  }

  @Override
  public String toString() {
    return "SerializedActivity { typeName = " + this.typeName + ", arguments = " + this.arguments.toString() + " }";
  }
}
