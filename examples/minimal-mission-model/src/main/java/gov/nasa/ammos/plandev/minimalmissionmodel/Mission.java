package gov.nasa.jpl.aerie.minimalmissionmodel;

import gov.nasa.jpl.aerie.contrib.models.Register;
import gov.nasa.jpl.aerie.contrib.serialization.mappers.EnumValueMapper;
import gov.nasa.jpl.aerie.merlin.framework.Registrar;

/** A contrived mission model that has only one discrete, non-numeric resource. */
public final class Mission {
  public enum GncControlMode {
    THRUSTERS,
    REACTION_WHEELS
  }
  public final Register<GncControlMode> gncControlMode = Register.forImmutable(GncControlMode.THRUSTERS);
  public Mission(final Registrar registrar, final Configuration config) {
    registrar.discrete("/gncControlMode", this.gncControlMode, new EnumValueMapper<>(GncControlMode.class));
  }
}
