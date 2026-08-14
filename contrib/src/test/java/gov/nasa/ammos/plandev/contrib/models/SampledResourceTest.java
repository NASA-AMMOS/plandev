package gov.nasa.jpl.aerie.contrib.models;

import gov.nasa.jpl.aerie.merlin.framework.junit.MerlinExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.delay;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECOND;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MerlinExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SampledResourceTest {
  final Accumulator accumulator = new Accumulator(0.0, 1.0);
  final SampledResource<Double> sampledResource = new SampledResource<>(() -> accumulator.get(), $ -> $, 10.0);

  @Test
  void testSampledResource() {
    assertEquals(0.0, sampledResource.get());
    delay(1, SECOND);
    assertEquals(0.0, sampledResource.get());
    delay(10, SECONDS);
    assertEquals(10.0, sampledResource.get());
    delay(5, SECONDS);
    assertEquals(10.0, sampledResource.get());
    delay(5, SECONDS);
    assertEquals(20.0, sampledResource.get());
  }
}
