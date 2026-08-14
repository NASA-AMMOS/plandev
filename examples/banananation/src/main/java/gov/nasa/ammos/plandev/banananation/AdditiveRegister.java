package gov.nasa.jpl.aerie.banananation;

import gov.nasa.jpl.aerie.contrib.models.counters.Counter;
import gov.nasa.jpl.aerie.merlin.framework.resources.discrete.DiscreteResource;

public final class AdditiveRegister implements DiscreteResource<Double> {
  public final Counter<Double> value;

  public AdditiveRegister(final double initialValue) {
    this.value = Counter.ofDouble(initialValue);
  }

  public static
  AdditiveRegister
  create(final double initialValue) {
    return new AdditiveRegister(initialValue);
  }

  @Override
  public Double getDynamics() {
    return this.value.getDynamics();
  }

  public void add(final double inc) {
    this.value.add(inc);
  }

  public void subtract(final double inc) {
    this.value.add(-inc);
  }
}
