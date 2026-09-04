package gov.nasa.ammos.plandev.banananation.activities;

import gov.nasa.ammos.plandev.banananation.Mission;
import gov.nasa.ammos.plandev.merlin.framework.annotations.ActivityType;
import gov.nasa.ammos.plandev.merlin.framework.annotations.ActivityType.ControllableDuration;
import gov.nasa.ammos.plandev.merlin.framework.annotations.ActivityType.EffectModel;
import gov.nasa.ammos.plandev.merlin.framework.annotations.Description;
import gov.nasa.ammos.plandev.merlin.framework.annotations.Export.Template;
import gov.nasa.ammos.plandev.merlin.framework.annotations.Export.Validation;
import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;

import static gov.nasa.ammos.plandev.merlin.framework.ModelActions.delay;

/**
 * Emits a controllable number of profile segments, so timeline rendering performance can be
 * exercised at a chosen scale without needing a real mission model that happens to produce large
 * profiles.
 *
 * <p>Segment count is what actually drives timeline cost, and it is a property of how a resource is
 * modeled rather than of simulation duration: an event-driven resource emits one segment per change
 * in dynamics, so even a multi-year horizon stays small, whereas a fixed-cadence resource emits
 * {@code duration / period} segments. This activity deliberately behaves like the latter — it is a
 * reproduction of the expensive case, not an example to imitate in a real model.
 *
 * <p>Both profile shapes are covered, because they drive different draw paths in the UI:
 * {@code /stress/real/*} are linear-dynamics real profiles (line layers) and
 * {@code /stress/discrete/*} are string profiles (x-range layers).
 */
@ActivityType("StressResources")
@Description("Emits a configurable number of profile segments for timeline performance testing")
public record StressResourcesActivity(
    int segmentCount,
    int realResourceCount,
    int discreteResourceCount,
    Duration duration
) {

  public static @Template StressResourcesActivity defaults() {
    return new StressResourcesActivity(10_000, 1, 0, Duration.of(24, Duration.HOUR));
  }

  @Validation("segmentCount must be positive")
  @Validation.Subject("segmentCount")
  public boolean validateSegmentCount() {
    return this.segmentCount() > 0;
  }

  @Validation("realResourceCount must be between 0 and the stress resource pool size")
  @Validation.Subject("realResourceCount")
  public boolean validateRealResourceCount() {
    return this.realResourceCount() >= 0 && this.realResourceCount() <= Mission.STRESS_RESOURCE_POOL_SIZE;
  }

  @Validation("discreteResourceCount must be between 0 and the stress resource pool size")
  @Validation.Subject("discreteResourceCount")
  public boolean validateDiscreteResourceCount() {
    return this.discreteResourceCount() >= 0 && this.discreteResourceCount() <= Mission.STRESS_RESOURCE_POOL_SIZE;
  }

  @Validation("at least one of realResourceCount or discreteResourceCount must be nonzero")
  @Validation.Subject("realResourceCount")
  public boolean validateSomeResourceSelected() {
    return this.realResourceCount() > 0 || this.discreteResourceCount() > 0;
  }

  @Validation("duration must be long enough to give every segment a distinct start time")
  @Validation.Subject("duration")
  public boolean validateDuration() {
    // Segment start offsets are unique per profile, and Duration resolution is one microsecond, so
    // more segments than microseconds cannot be represented.
    return this.duration().in(Duration.MICROSECONDS) >= this.segmentCount();
  }

  @EffectModel
  @ControllableDuration(parameterName = "duration")
  public void run(final Mission mission) {
    final var totalMicros = this.duration().in(Duration.MICROSECONDS);
    final var step = Duration.of(totalMicros / this.segmentCount(), Duration.MICROSECONDS);
    final var stepSeconds = step.in(Duration.MICROSECONDS) / 1_000_000.0;
    final var totalSeconds = totalMicros / 1_000_000.0;

    // Accumulator exposes rate as a delta, so track the rate we last applied per resource in order
    // to drive it to an absolute target.
    final var currentRate = new double[this.realResourceCount()];

    // These resources integrate their rate, so a spike must be scaled to the step to produce a
    // value excursion of a given size, and that size must not depend on segmentCount -- otherwise
    // spikes vanish at exactly the scales worth testing. See spikeRateFor.
    final var spikeRate = new double[this.realResourceCount()];
    for (int r = 0; r < this.realResourceCount(); r++) {
      spikeRate[r] = spikeRateFor(r, totalSeconds, stepSeconds);
    }

    for (int i = 0; i < this.segmentCount(); i++) {
      final var progress = i / (double) this.segmentCount();

      for (int r = 0; r < this.realResourceCount(); r++) {
        final var targetRate = rateAt(progress, i, r, spikeRate[r]);
        mission.stressReal.get(r).rate.add(targetRate - currentRate[r]);
        currentRate[r] = targetRate;
      }

      for (int r = 0; r < this.discreteResourceCount(); r++) {
        // Runs of 7 identical labels, so the x-range layer's run-coalescing is exercised rather
        // than every point becoming its own box.
        mission.stressDiscrete.get(r).set("state-" + (((i / 7) + r) % 5));
      }

      // The final segment is left open; the profile's duration closes it.
      if (i < this.segmentCount() - 1) {
        delay(step);
      }
    }

    // Integer division above drops a remainder each step, so settle on the declared duration to
    // keep the activity's span equal to its controllable duration parameter.
    final var remainder = this.duration().minus(step.times(this.segmentCount() - 1));
    if (remainder.isPositive()) {
      delay(remainder);
    }
  }

  /** Rate amplitude of the slow sine, in bananas per second. */
  private static final double BASE_AMPLITUDE = 10.0;

  /** Spike height as a fraction of the sine's peak-to-peak value range. */
  private static final double SPIKE_FRACTION = 0.05;

  /**
   * Segments between spikes. Prime, so spikes never settle into a repeating alignment with pixel
   * columns at any zoom level — an aligned pattern could make a broken decimator look correct.
   */
  private static final int SPIKE_INTERVAL = 997;

  /** Sine cycles across the activity's span, varied per resource so rows are distinguishable. */
  private static int cyclesFor(final int resource) {
    return 8 + resource;
  }

  /**
   * Rate needed to move the integrated value by {@link #SPIKE_FRACTION} of its range within a single
   * segment.
   *
   * <p>Integrating a sine of amplitude {@code A} over a span {@code T} containing {@code n} cycles
   * gives a peak-to-peak value range of {@code A*T/(pi*n)}. Dividing the target excursion by the
   * segment length converts it to a rate, which keeps spike height independent of
   * {@code segmentCount} — a fixed rate would produce excursions that shrink to invisibility as
   * segment count grows, i.e. exactly where spike preservation matters most.
   */
  private static double spikeRateFor(final int resource, final double totalSeconds, final double stepSeconds) {
    final var valueRange = BASE_AMPLITUDE * totalSeconds / (Math.PI * cyclesFor(resource));
    return (SPIKE_FRACTION * valueRange) / stepSeconds;
  }

  /**
   * A slow sine gives broad structure at every zoom level. On top of it, a rare spike is applied as
   * an equal and opposite pair of segments, so the integrated value jumps away from the trend and
   * immediately returns to it.
   *
   * <p>The pair matters: these resources integrate their rate, so a one-sided rate bump would leave
   * a permanent step in the value rather than a spike, and a step is not what decimation has to work
   * to preserve. A narrow returning excursion is — min/max keeps it, point-skipping drops it — which
   * makes rendering correctness checkable by eye.
   */
  private static double rateAt(final double progress, final int index, final int resource, final double spikeRate) {
    final var base = Math.sin(progress * 2 * Math.PI * cyclesFor(resource)) * BASE_AMPLITUDE;
    if (index % SPIKE_INTERVAL == 0) {
      return base + spikeRate;
    }
    if (index % SPIKE_INTERVAL == 1) {
      return base - spikeRate;
    }
    return base;
  }
}
