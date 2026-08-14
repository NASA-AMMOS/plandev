package gov.nasa.jpl.aerie.contrib.models;

import gov.nasa.jpl.aerie.merlin.framework.resources.discrete.DiscreteResource;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.*;

/**
 * Simple resource that samples arbitrarily many existing resources/values at a specified period (default period is once
 * per second).
 */
public class SampledResource<T> implements DiscreteResource<T> {
  private final Register<T> result;
  private final Supplier<T> sampler;
  private final Register<Double> period;

  /**
   * Constructor that does not require caller to specify a period and therefore assumes a sample
   * period of 1 sample per second.
   */
  public SampledResource(final Supplier<T> sampler, final UnaryOperator<T> duplicator) {
    this(sampler, duplicator, 1.0);
  }

  /**
   * Constructor that requires caller to specify an initial sample period
   */
  public SampledResource(final Supplier<T> sampler, final UnaryOperator<T> duplicator, final double period) {
    this.result = Register.create(sampler.get(), duplicator);
    this.sampler = Objects.requireNonNull(sampler);
    this.period =  Register.forImmutable(period);
    spawn(this::takeSamples);
  }

  /**
   * Method that samples the supplied resource at the current specified period
   */
  private void takeSamples() {
    while (true) {
      var sample = sampler.get();
      if (!result.get().equals(sample)) {
        result.set(sample);
      }
      delay( Duration.roundNearest(period.get(), Duration.SECONDS) );
    }
  }

  /**
   * Get current sample period (seconds per sample)
   */
  public double getPeriod() { return period.get(); }

  /**
   * Method to adjust the specified period of sampling. Note if takeSamples() is currently waiting, the
   * new period will not take effect until after the current wait cycle.
   */
  public void setPeriod(final double newPeriod ) { period.set( newPeriod ); }

  @Override
  public T getDynamics() {
    return this.result.getDynamics();
  }

  @Deprecated
  @Override
  public boolean equals(final Object obj) {
    return super.equals(obj);
  }
}
