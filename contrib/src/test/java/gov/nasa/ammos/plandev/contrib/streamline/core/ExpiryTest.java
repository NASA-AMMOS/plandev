package gov.nasa.jpl.aerie.contrib.streamline.core;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static gov.nasa.jpl.aerie.contrib.streamline.core.Expiry.NEVER;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Expiry.at;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Expiry.expiry;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.HOUR;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.MINUTE;
import static org.junit.jupiter.api.Assertions.*;

class ExpiryTest {
  @Nested
  class Value {
    @Test
    void never_expiring_has_empty_value() {
      assertEquals(Optional.empty(), NEVER.value());
    }

    @Test
    void expiring_at_t_has_value_of_t() {
      assertEquals(Optional.of(MINUTE), Expiry.at(MINUTE).value());
    }
  }

  @Nested
  class Equals {
    @Test
    void never_equals_never() {
      assertEquals(NEVER, NEVER);
      assertEquals(NEVER, expiry(Optional.empty()));
      assertEquals(expiry(Optional.empty()), NEVER);
      assertEquals(expiry(Optional.empty()), expiry(Optional.empty()));
    }

    @Test
    void at_t_equals_at_t() {
      assertEquals(at(MINUTE), at(MINUTE));
    }

    @Test
    void at_t_does_not_equal_never() {
      assertNotEquals(at(MINUTE), NEVER);
      assertNotEquals(NEVER, at(MINUTE));
    }

    @Test
    void at_t_does_not_equal_at_s() {
      assertNotEquals(at(MINUTE), at(HOUR));
      assertNotEquals(at(HOUR), at(MINUTE));
    }
  }

  @Nested
  class IsNever {
    @Test
    void never_is_never() {
      assertTrue(NEVER.isNever());
    }

    @Test
    void at_t_is_not_never() {
      assertFalse(at(MINUTE).isNever());
    }
  }

  @Nested
  class Or {
    @Test
    void never_or_never_returns_never() {
      assertEquals(NEVER, NEVER.or(NEVER));
    }

    @Test
    void never_or_at_t_returns_at_t() {
      assertEquals(at(MINUTE), NEVER.or(at(MINUTE)));
    }

    @Test
    void at_t_or_never_returns_at_t() {
      assertEquals(at(MINUTE), at(MINUTE).or(NEVER));
    }

    @Test
    void at_t_or_at_greater_than_t_returns_at_t() {
      assertEquals(at(MINUTE), at(MINUTE).or(at(HOUR)));
    }

    @Test
    void at_greater_than_t_or_at_t_returns_at_t() {
      assertEquals(at(MINUTE), at(HOUR).or(at(MINUTE)));
    }
  }

  @Nested
  class Minus {
    @Test
    void never_minus_t_returns_never() {
      assertEquals(NEVER, NEVER.minus(MINUTE));
    }

    @Test
    void at_t_minus_s_returns_at_difference() {
      assertEquals(at(HOUR.minus(MINUTE)), at(HOUR).minus(MINUTE));
    }
  }

  @Nested
  class Comparisons {
    @Test
    void never_equals_never() {
      assertFalse(NEVER.shorterThan(NEVER));
      assertTrue(NEVER.noShorterThan(NEVER));
      assertFalse(NEVER.longerThan(NEVER));
      assertTrue(NEVER.noLongerThan(NEVER));
    }

    @Test
    void at_t_equals_at_t() {
      assertFalse(at(MINUTE).shorterThan(at(MINUTE)));
      assertTrue(at(MINUTE).noShorterThan(at(MINUTE)));
      assertFalse(at(MINUTE).longerThan(at(MINUTE)));
      assertTrue(at(MINUTE).noLongerThan(at(MINUTE)));
    }

    @Test
    void at_t_shorter_than_never() {
      assertTrue(at(MINUTE).shorterThan(NEVER));
      assertFalse(at(MINUTE).noShorterThan(NEVER));
      assertFalse(at(MINUTE).longerThan(NEVER));
      assertTrue(at(MINUTE).noLongerThan(NEVER));
    }

    @Test
    void never_longer_than_at_t() {
      assertFalse(NEVER.shorterThan(at(MINUTE)));
      assertTrue(NEVER.noShorterThan(at(MINUTE)));
      assertTrue(NEVER.longerThan(at(MINUTE)));
      assertFalse(NEVER.noLongerThan(at(MINUTE)));
    }

    @Test
    void at_t_shorter_than_at_greater_than_t() {
      assertTrue(at(MINUTE).shorterThan(at(HOUR)));
      assertFalse(at(MINUTE).noShorterThan(at(HOUR)));
      assertFalse(at(MINUTE).longerThan(at(HOUR)));
      assertTrue(at(MINUTE).noLongerThan(at(HOUR)));
    }
  }
}
