package gov.nasa.jpl.plandev.procedural.constraints

import gov.nasa.jpl.plandev.merlin.protocol.types.SerializedValue
import gov.nasa.jpl.plandev.merlin.protocol.types.ValueSchema

interface ProcedureMapper<T: Constraint> {
  fun valueSchema(): ValueSchema
  fun serialize(procedure: T): SerializedValue
  fun deserialize(arguments: SerializedValue): T
}
