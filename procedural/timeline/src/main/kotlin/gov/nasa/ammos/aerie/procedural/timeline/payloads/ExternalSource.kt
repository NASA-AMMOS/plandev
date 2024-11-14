package gov.nasa.ammos.aerie.procedural.timeline.payloads

import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue

/**
 * An external source instance.
 * Used either to represent the source associated with a given event. Not used for querying.
 * The included fields are the primary key used to identify External Sources, and the source's
 *    attributes.
 */
data class ExternalSource(
  /** The string name of this source. */
  @JvmField
  val key: String,
  /** The derivation group that this source is a member of. */
  @JvmField
  val derivationGroup: String,
  /** The attributes of the event. */
  @JvmField
  val attributes: Map<String, SerializedValue>,
)
