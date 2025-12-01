package gov.nasa.jpl.plandev.streamline_demo;

import gov.nasa.jpl.plandev.merlin.framework.annotations.Export.Parameter;
import gov.nasa.jpl.plandev.merlin.protocol.types.Duration;

public final class Configuration {
  @Parameter
  public boolean traceResources = false;

  @Parameter
  public boolean profileResources = false;

  @Parameter
  public double approximationTolerance = 1e-2;

  @Parameter
  public Duration profilingDumpTime = Duration.ZERO;

}
