package gov.nasa.ammos.aerie.procedural.scheduling

import gov.nasa.jpl.aerie.merlin.protocol.model.InputType
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema

interface ProcedureMapper<T: Goal> {
  fun valueSchema(): ValueSchema
  fun getInputType(): InputType<T>
}
