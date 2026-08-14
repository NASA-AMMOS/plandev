package gov.nasa.jpl.aerie.foomissionmodel;

import gov.nasa.jpl.aerie.merlin.framework.annotations.Export;

public final class Configuration {

  @Export.Parameter
  public Double sinkRate;

  // If enabled, will raise an exception 30 min into a plan
  @Export.Parameter
  public Boolean raiseException;

  public Configuration(final Double sinkRate, final Boolean raiseException) {
    this.sinkRate = sinkRate;
    this.raiseException = raiseException;
  }

  public Configuration(final Double sinkRate) {
    this(sinkRate, false);
  }

  public Configuration() {
    this(0.5, false);
  }
}
