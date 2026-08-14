package gov.nasa.jpl.aerie.foomissionmodel.activities;

import gov.nasa.jpl.aerie.foomissionmodel.Mission;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export;

import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.delay;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECOND;

@ActivityType("SolarPanelNonLinearTimeDependent")
public class SolarPanelNonLinearTimeDependent {

  //orbit period = 2 weeks
  static final double PERIODSECS = 3600 * 24 * 14.;
  //coeff for cos function
  static final double a = (2 * Math.PI) / PERIODSECS;

  public double getAngleAtTime(double x){
    return Math.cos(a*x);
  }

//what command it should achieve
  @Export.Parameter
  public double command = 2.;

  //max acceleration in radians/s2
  @Export.Parameter
  public double alpha_max = 0.0001;

  //max rate in rad/s
  @Export.Parameter
  public double omega_max = 0.01;


  @ActivityType.EffectModel
  public void run(final Mission mission) {
    final var curAngle =  getAngleAtTime(mission.utcClock.getElapsedMilliseconds());
    delay((long)SolarPanelNonLinear.duration(Math.abs(curAngle - command), alpha_max, omega_max), SECOND);
  }

}
