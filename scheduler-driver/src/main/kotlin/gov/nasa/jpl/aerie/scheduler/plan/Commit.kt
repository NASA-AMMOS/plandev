package gov.nasa.jpl.aerie.scheduler.plan

import gov.nasa.ammos.aerie.procedural.scheduling.plan.Edit

data class Commit(
    val diff: List<Edit>,
)
