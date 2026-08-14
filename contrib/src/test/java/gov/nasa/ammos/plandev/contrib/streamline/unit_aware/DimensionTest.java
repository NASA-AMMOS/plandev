package gov.nasa.jpl.aerie.contrib.streamline.unit_aware;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static gov.nasa.jpl.aerie.contrib.streamline.unit_aware.Dimension.SCALAR;
import static gov.nasa.jpl.aerie.contrib.streamline.unit_aware.Rational.*;
import static gov.nasa.jpl.aerie.contrib.streamline.unit_aware.StandardDimensions.*;
import static org.junit.jupiter.api.Assertions.*;

class DimensionTest {
  @Nested
  class BaseDimensions {
    @Test
    void equal_themselves() {
      assertEquals(LENGTH, LENGTH);
    }

    @Test
    void are_distinct() {
      assertNotEquals(LENGTH, TIME);
    }

    @Test
    void satisfy_is_base_check() {
      assertTrue(LENGTH.isBase());
    }
  }

  @Nested
  class DimensionProducts {
    @Test
    void are_distinct_from_both_factors() {
      Dimension MASS_LENGTH = MASS.multiply(LENGTH);
      assertNotEquals(MASS, MASS_LENGTH);
      assertNotEquals(LENGTH, MASS_LENGTH);
    }

    @Test
    void are_equal_to_identically_derived_dimensions() {
      Dimension MASS_LENGTH_1 = MASS.multiply(LENGTH);
      Dimension MASS_LENGTH_2 = MASS.multiply(LENGTH);
      assertEquals(MASS_LENGTH_1, MASS_LENGTH_2);
    }

    @Test
    void are_not_equal_to_different_products() {
      Dimension MASS_LENGTH = MASS.multiply(LENGTH);
      Dimension MASS_TIME = MASS.multiply(TIME);
      assertNotEquals(MASS_LENGTH, MASS_TIME);
    }

    @Test
    void fail_is_base_check() {
      Dimension MASS_LENGTH = MASS.multiply(LENGTH);
      assertFalse(MASS_LENGTH.isBase());
    }

    @Test
    void commute() {
      Dimension MASS_LENGTH = MASS.multiply(LENGTH);
      Dimension LENGTH_MASS = LENGTH.multiply(MASS);
      assertEquals(MASS_LENGTH, LENGTH_MASS);
    }

    @Test
    void associate() {
      Dimension MASS_LENGTH_TIME = MASS.multiply(LENGTH).multiply(TIME);
      Dimension MASS_LENGTH_TIME_2 = MASS.multiply(LENGTH.multiply(TIME));
      assertEquals(MASS_LENGTH_TIME, MASS_LENGTH_TIME_2);
    }

    @Test
    void have_scalar_as_left_identity() {
      Dimension MASS_2 = SCALAR.multiply(MASS);
      assertEquals(MASS, MASS_2);
    }

    @Test
    void have_scalar_as_right_identity() {
      Dimension MASS_2 = MASS.multiply(SCALAR);
      assertEquals(MASS, MASS_2);
    }
  }

  @Nested
  class DimensionQuotients {
    @Test
    void are_distinct_from_both_factors() {
      assertNotEquals(LENGTH, LENGTH.divide(TIME));
      assertNotEquals(TIME, LENGTH.divide(TIME));
    }

    @Test
    void are_equal_to_identically_derived_dimensions() {
      assertEquals(LENGTH.divide(TIME), LENGTH.divide(TIME));
    }

    @Test
    void are_not_equal_to_different_quotients() {
      assertNotEquals(LENGTH.divide(TIME), MASS.divide(TIME));
    }

    @Test
    void fail_is_base_check() {
      assertFalse(LENGTH.divide(TIME).isBase());
    }

    @Test
    void do_not_commute() {
      assertNotEquals(LENGTH.divide(TIME), TIME.divide(LENGTH));
    }

    @Test
    void do_not_have_scalar_as_left_identity() {
      assertNotEquals(MASS, SCALAR.divide(MASS));
    }

    @Test
    void have_scalar_as_right_identity() {
      assertEquals(MASS, MASS.divide(SCALAR));
    }

    @Test
    void invert_dimension_products() {
      assertEquals(MASS, MASS.multiply(LENGTH).divide(LENGTH));
    }

    @Test
    void are_inverted_by_dimension_products() {
      assertEquals(MASS, MASS.divide(LENGTH).multiply(LENGTH));
    }
  }

  @Nested
  class DimensionPowers {
    @Test
    void have_one_as_right_identity() {
      assertEquals(MASS, MASS.power(ONE));
    }

    @Test
    void have_zero_as_right_annihilator() {
      assertEquals(SCALAR, MASS.power(ZERO));
    }

    @Test
    void have_scalar_as_left_annihilator() {
      assertEquals(SCALAR, SCALAR.power(rational(2)));
      assertEquals(SCALAR, SCALAR.power(rational(-2)));
      assertEquals(SCALAR, SCALAR.power(rational(0)));
    }

    @Test
    void are_equal_to_repeated_multiplication_for_positive_integer_powers() {
      assertEquals(MASS.multiply(MASS), MASS.power(rational(2)));
      assertEquals(MASS.multiply(MASS).multiply(MASS), MASS.power(rational(3)));
    }

    @Test
    void are_equal_to_repeated_division_for_negative_integer_powers() {
      assertEquals(SCALAR.divide(MASS), MASS.power(rational(-1)));
      assertEquals(SCALAR.divide(MASS).divide(MASS), MASS.power(rational(-2)));
    }

    @Test
    void distribute_over_products() {
      var p = rational(2, 3);
      assertEquals(MASS.power(p).multiply(LENGTH.power(p)), MASS.multiply(LENGTH).power(p));
    }

    @Test
    void distribute_over_quotients() {
      var p = rational(2, 3);
      assertEquals(MASS.power(p).divide(LENGTH.power(p)), MASS.divide(LENGTH).power(p));
    }

    @Test
    void add_exactly_when_multiplying_common_bases() {
      var p = rational(2, 3);
      var q = rational(1, 3);
      assertEquals(MASS, MASS.power(p).multiply(MASS.power(q)));
    }

    @Test
    void subtract_exactly_when_multiplying_common_bases() {
      var p = rational(4, 3);
      var q = rational(1, 3);
      assertEquals(MASS, MASS.power(p).divide(MASS.power(q)));
    }
  }
}
