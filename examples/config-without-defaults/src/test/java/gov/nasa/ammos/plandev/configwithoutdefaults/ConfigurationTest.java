package gov.nasa.jpl.aerie.configwithoutdefaults;

import gov.nasa.jpl.aerie.configwithoutdefaults.generated.ConfigurationMapper;
import gov.nasa.jpl.aerie.merlin.framework.Registrar;
import gov.nasa.jpl.aerie.merlin.framework.junit.MerlinExtension;
import gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(Lifecycle.PER_CLASS)
@ExtendWith(MerlinExtension.class)
public final class ConfigurationTest {
  private static final Integer a = 42;
  private static final Double b = 3.14;
  private static final String c = "JPL";

  private final Mission model;

  public ConfigurationTest(final Registrar registrar)
  throws InstantiationException
  {
    final var config = new ConfigurationMapper().instantiate(Map.of(
        "a", SerializedValue.of(a),
        "b", SerializedValue.of(b),
        "c", SerializedValue.of(c)
    ));

    this.model = new Mission(registrar, config);
  }

  @Test
  public void testDefaults() {
    assertThat(model.configuration.a()).isEqualTo(a);
    assertThat(model.configuration.b()).isEqualTo(b);
    assertThat(model.configuration.c()).isEqualTo(c);
  }
}
