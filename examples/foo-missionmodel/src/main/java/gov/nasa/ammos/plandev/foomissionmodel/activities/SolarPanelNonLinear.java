package gov.nasa.jpl.aerie.foomissionmodel.activities;

import gov.nasa.jpl.aerie.foomissionmodel.Mission;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;

import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.delay;
import static gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType.EffectModel;
import static gov.nasa.jpl.aerie.merlin.framework.annotations.Export.Parameter;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECOND;

/**
 * Activity written to demonstrate how the scheduler might handle uncontrollable durations.
 * This is a representation of a solar articulation activity
 *
 For long articulations, the articulation is split into three segments: (1) acceleration leg, (2) coast leg (no accel),
 (3) deceleration leg. The arrays will accelerate at the max accel up to the max rate, coast for some time at the max
 rate, and decelerate to zero to complete the turn (where the array has now rotated by the turn angle). This is a
 trapezoidal profile (since the angular velocity curve is shaped like a trapezoid).

 For shorter articulations, we may never accelerate long enough to hit the max rate; we just accelerate for a little bit
 and start decelerating (all at the max accel). This is what we’d call a triangular profile.
 */

@ActivityType("SolarPanelNonLinear")
public final class SolarPanelNonLinear {
  //turn angle command in radians
  @Parameter
  public double theta_turn = 2.;

  //max acceleration in radians/s2
  @Parameter
  public double alpha_max = 0.0001;

  //max rate in rad/s
  @Parameter
  public double omega_max = 0.01;

  public static double duration(double theta_turn, double alpha_max, double omega_max) {
    final var t_accel = Math.min(Math.sqrt(theta_turn/alpha_max), omega_max/alpha_max);
    final var t_coast = (theta_turn - alpha_max * Math.pow(t_accel, 2))/omega_max;
    final var t_decel = t_accel;
    final var t_total = t_accel + t_coast + t_decel;
    return t_total;
  }

  @EffectModel
  public void run(final Mission mission) {
    delay((long)duration(theta_turn, alpha_max, omega_max), SECOND);
  }
}
