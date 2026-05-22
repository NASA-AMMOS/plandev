package gov.nasa.ammos.aerie.procedural.timeline.payloads.reference

import gov.nasa.ammos.aerie.procedural.timeline.payloads.ExternalEvent

data class ResourceActivitySource (
  override val v: String
): ActivitySource<String>(v) {
//  init {
//    require(
//  }
  // TODO: find some way to verify that the resource name is real...?
  //        may need to take the simulation and plan as parameters...
  // TODO: cache that these exist based on calls to simulate().resource()??
}
