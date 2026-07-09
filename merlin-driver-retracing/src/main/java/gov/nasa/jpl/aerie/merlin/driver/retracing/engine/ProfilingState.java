package gov.nasa.jpl.aerie.merlin.driver.retracing.engine;

import gov.nasa.jpl.aerie.merlin.protocol.driver.Querier;
import gov.nasa.jpl.aerie.merlin.protocol.model.Resource;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

/*package-local*/
record ProfilingState<Dynamics> (Resource<Dynamics> resource, Profile<Dynamics> profile) {
  public static <DynamicsType>
  ProfilingState<DynamicsType> create(final Resource<DynamicsType> resource) {
    return new ProfilingState<>(resource, new Profile<>());
  }

  public void append(final Duration currentTime, final Querier querier) {
    this.profile.append(currentTime, this.resource.getDynamics(querier));
  }
}
