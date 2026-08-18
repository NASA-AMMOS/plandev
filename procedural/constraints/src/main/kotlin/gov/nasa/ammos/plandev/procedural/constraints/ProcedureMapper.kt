package gov.nasa.ammos.plandev.procedural.constraints

import gov.nasa.ammos.plandev.merlin.protocol.model.InputType
import gov.nasa.ammos.plandev.merlin.protocol.types.ValueSchema

interface ProcedureMapper<T: Constraint> {
  fun valueSchema(): ValueSchema
  fun getInputType(): InputType<T>
}
