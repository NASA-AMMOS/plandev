package gov.nasa.jpl.plandev.procedural.scheduling

import gov.nasa.jpl.plandev.merlin.protocol.types.SerializedValue
import gov.nasa.jpl.plandev.merlin.protocol.types.ValueSchema

interface ProcedureMapper<T: Goal> {
  fun valueSchema(): ValueSchema
  fun serialize(procedure: T): SerializedValue
  fun deserialize(arguments: SerializedValue): T
}
