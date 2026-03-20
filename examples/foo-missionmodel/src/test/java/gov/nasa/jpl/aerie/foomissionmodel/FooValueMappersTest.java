package gov.nasa.jpl.aerie.foomissionmodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.nasa.jpl.aerie.foomissionmodel.generated.ConfigurationMapper;
import gov.nasa.jpl.aerie.merlin.driver.json.JsonEncoding;
import gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

public final class FooValueMappersTest {
  @Test
  public void testConfigurationMapper() throws InstantiationException, IOException {
    final var stream = FooValueMappersTest.class.getResourceAsStream("mission_config.json");
    final var serializedConfig = JsonEncoding.decode(new ObjectMapper().readTree(stream));
    final var config = new ConfigurationMapper().instantiate(serializedConfig.asMap().get());

    assertThat(config.sinkRate).isCloseTo(42.0, within(1e-9));
  }
}
