package gov.nasa.ammos.plandev.configwithoutdefaults;

import gov.nasa.ammos.plandev.contrib.models.Register;
import gov.nasa.ammos.plandev.contrib.serialization.mappers.DoubleValueMapper;
import gov.nasa.ammos.plandev.contrib.serialization.mappers.IntegerValueMapper;
import gov.nasa.ammos.plandev.contrib.serialization.mappers.StringValueMapper;
import gov.nasa.ammos.plandev.merlin.framework.Registrar;

/** A contrived mission model that simply reports the configuration's values. */
public final class Mission {

  public final Configuration configuration;

  public Mission(final Registrar registrar, final Configuration config) {
    this.configuration = config;
    registrar.discrete("/a", Register.forImmutable(config.a()), new IntegerValueMapper());
    registrar.discrete("/b", Register.forImmutable(config.b()), new DoubleValueMapper());
    registrar.discrete("/c", Register.forImmutable(config.c()), new StringValueMapper());
  }
}
