package gov.nasa.jpl.aerie.timeline.payloads.activities

import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue
import kotlin.jvm.optionals.getOrNull

/** A general-purpose container for representing the arguments any type of activity directive. */
data class AnyDirective(
    /***/ @JvmField val arguments: Map<String, SerializedValue>
) {
  /** Serialize this directive into a [SerializedValue]. */
  fun serialize(): SerializedValue = SerializedValue.of(arguments)
  /***/ companion object {
    /** Converts a [SerializedValue] object containing activity arguments into an [AnyDirective] object. */
    @JvmStatic fun deserialize(attributes: SerializedValue) = AnyDirective(attributes.asMap().getOrNull()!!)
  }
}
