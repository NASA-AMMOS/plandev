package gov.nasa.ammos.plandev.procedural.scheduling

import gov.nasa.ammos.plandev.merlin.protocol.model.InputType
import gov.nasa.ammos.plandev.merlin.protocol.types.ValueSchema

interface ProcedureMapper<T: Goal> {
  fun valueSchema(): ValueSchema
  fun getInputType(): InputType<T>
}
