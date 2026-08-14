package gov.nasa.jpl.aerie.foomissionmodel;

import gov.nasa.jpl.aerie.foomissionmodel.activities.FooActivity;
import gov.nasa.jpl.aerie.merlin.framework.Registrar;
import gov.nasa.jpl.aerie.merlin.framework.junit.MerlinExtension;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;

import static gov.nasa.jpl.aerie.foomissionmodel.generated.ActivityActions.spawn;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.delay;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

public final class MissionConfigurationTest {
  @TestInstance(Lifecycle.PER_CLASS)
  @ExtendWith(MerlinExtension.class)
  public @Nested final class Test1 {
    private final Mission model;

    public Test1(final Registrar registrar) {
      this.model = new Mission(registrar, Instant.EPOCH, new Configuration());
    }

    @Test
    public void test() {
      assertThat(model.startingAfterUnixEpoch.get()).isEqualTo(false);
      spawn(model, new FooActivity());
      delay(1, Duration.SECOND);
      assertThat(model.sink.get()).isCloseTo(0.5, within(1e-9));
    }
  }

  @TestInstance(Lifecycle.PER_CLASS)
  @ExtendWith(MerlinExtension.class)
  public @Nested final class Test2 {
    private final Mission model;

    public Test2(final Registrar registrar) {
      this.model = new Mission(registrar, Instant.EPOCH.plusSeconds(1), new Configuration(2.0));
    }

    @Test
    public void test() {
      assertThat(model.startingAfterUnixEpoch.get()).isEqualTo(true);
      spawn(model, new FooActivity());
      delay(1, Duration.SECOND);
      assertThat(model.sink.get()).isCloseTo(2.0, within(1e-9));
    }
  }
}
