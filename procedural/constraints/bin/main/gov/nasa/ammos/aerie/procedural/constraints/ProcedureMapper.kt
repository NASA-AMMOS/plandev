package gov.nasa.ammos.aerie.procedural.constraints

import gov.nasa.jpl.aerie.merlin.protocol.model.InputType
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema

interface ProcedureMapper<T: Constraint> {
  fun valueSchema(): ValueSchema
  fun getInputType(): InputType<T>
}
