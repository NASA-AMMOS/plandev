package gov.nasa.jpl.aerie.configwithoutdefaults;

import gov.nasa.jpl.aerie.contrib.models.Register;
import gov.nasa.jpl.aerie.contrib.serialization.mappers.DoubleValueMapper;
import gov.nasa.jpl.aerie.contrib.serialization.mappers.IntegerValueMapper;
import gov.nasa.jpl.aerie.contrib.serialization.mappers.StringValueMapper;
import gov.nasa.jpl.aerie.merlin.framework.Registrar;

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
