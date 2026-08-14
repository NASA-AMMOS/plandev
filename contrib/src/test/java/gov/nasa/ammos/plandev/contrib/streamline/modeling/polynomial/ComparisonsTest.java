package gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial;

import gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource;
import gov.nasa.jpl.aerie.contrib.streamline.core.Expiry;
import gov.nasa.jpl.aerie.contrib.streamline.core.Resource;
import gov.nasa.jpl.aerie.contrib.streamline.core.Resources;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.Discrete;
import gov.nasa.jpl.aerie.merlin.framework.Registrar;
import gov.nasa.jpl.aerie.merlin.framework.junit.MerlinExtension;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.extension.ExtendWith;

import static gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource.resource;
import static gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource.set;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Resources.currentData;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Resources.currentValue;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.Polynomial.polynomial;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.PolynomialResources.*;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.delay;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.EPSILON;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECOND;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.ZERO;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MerlinExtension.class)
@TestInstance(Lifecycle.PER_CLASS)
public class ComparisonsTest {
  public ComparisonsTest(final Registrar registrar) {
    Resources.init();
  }

  private final MutableResource<Polynomial> p = resource(polynomial(0));
  private final MutableResource<Polynomial> q = resource(polynomial(0));

  private final Resource<Discrete<Boolean>> p_lt_q = lessThan(p, q);
  private final Resource<Discrete<Boolean>> p_lte_q = lessThanOrEquals(p, q);
  private final Resource<Discrete<Boolean>> p_gt_q = greaterThan(p, q);
  private final Resource<Discrete<Boolean>> p_gte_q = greaterThanOrEquals(p, q);

  private final Resource<Polynomial> min_p_q = min(p, q);
  private final Resource<Polynomial> min_q_p = min(q, p);
  private final Resource<Polynomial> max_p_q = max(p, q);
  private final Resource<Polynomial> max_q_p = max(q, p);

  @Test
  void comparing_distinct_constants() {
    setup(() -> {
      set(p, polynomial(0));
      set(q, polynomial(1));
    });

    check_comparison(p_lt_q, true, false);
    check_comparison(p_lte_q, true, false);
    check_comparison(p_gt_q, false, false);
    check_comparison(p_gte_q, false, false);
  }

  @Test
  void comparing_equal_constants() {
    setup(() -> {
      set(p, polynomial(1));
      set(q, polynomial(1));
    });

    check_comparison(p_lt_q, false, false);
    check_comparison(p_lte_q, true, false);
    check_comparison(p_gt_q, false, false);
    check_comparison(p_gte_q, true, false);
  }

  @Test
  void comparing_diverging_linear_terms() {
    setup(() -> {
      set(p, polynomial(0, 1));
      set(q, polynomial(1, 2));
    });

    check_comparison(p_lt_q, true, false);
    check_comparison(p_lte_q, true, false);
    check_comparison(p_gt_q, false, false);
    check_comparison(p_gte_q, false, false);
  }

  @Test
  void comparing_converging_linear_terms() {
    setup(() -> {
      set(p, polynomial(0, 1));
      set(q, polynomial(2, -1));
    });
    check_comparison(p_lt_q, true, true);
    check_comparison(p_lte_q, true, true);
    check_comparison(p_gt_q, false, true);
    check_comparison(p_gte_q, false, true);
  }

  @Test
  void comparing_equal_linear_terms() {
    setup(() -> {
      set(p, polynomial(0, 1));
      set(q, polynomial(0, 1));
    });
    check_comparison(p_lt_q, false, false);
    check_comparison(p_lte_q, true, false);
    check_comparison(p_gt_q, false, false);
    check_comparison(p_gte_q, true, false);
  }

  @Test
  void comparing_equal_then_diverging_linear_terms() {
    setup(() -> {
      set(p, polynomial(0, 1));
      set(q, polynomial(0, 2));
    });
    // Notice that LT is initially false, but will immediately cross over
    check_comparison(p_lt_q, false, true);
    check_comparison(p_lte_q, true, false);
    check_comparison(p_gt_q, false, false);
    // Notice that GTE is initially true, but will immediately cross over
    check_comparison(p_gte_q, true, true);
  }

  @Test
  void comparing_diverging_nonlinear_terms() {
    setup(() -> {
      set(p, polynomial(0, 1, 1));
      set(q, polynomial(1, 2, 2));
    });
    check_comparison(p_lt_q, true, false);
    check_comparison(p_lte_q, true, false);
    check_comparison(p_gt_q, false, false);
    check_comparison(p_gte_q, false, false);
  }

  @Test
  void comparing_converging_nonlinear_terms() {
    setup(() -> {
      set(p, polynomial(0, 1, 1));
      set(q, polynomial(1, 2, -1));
    });
    check_comparison(p_lt_q, true, true);
    check_comparison(p_lte_q, true, true);
    check_comparison(p_gt_q, false, true);
    check_comparison(p_gte_q, false, true);
  }

  @Test
  void comparing_equal_nonlinear_terms() {
    setup(() -> {
      set(p, polynomial(1, 2, -1));
      set(q, polynomial(1, 2, -1));
    });
    check_comparison(p_lt_q, false, false);
    check_comparison(p_lte_q, true, false);
    check_comparison(p_gt_q, false, false);
    check_comparison(p_gte_q, true, false);
  }

  @Test
  void comparing_equal_then_diverging_nonlinear_terms() {
    setup(() -> {
      set(p, polynomial(1, 2, -1));
      set(q, polynomial(1, 2, 1));
    });
    // Notice that LT is initially false, but will immediately cross over
    check_comparison(p_lt_q, false, true);
    check_comparison(p_lte_q, true, false);
    check_comparison(p_gt_q, false, false);
    // Notice that GTE is initially true, but will immediately cross over
    check_comparison(p_gte_q, true, true);
  }

  @Test
  void extrema_of_equal_resources() {
    setup(() -> {
      set(p, polynomial(1, 2, -1));
      set(q, polynomial(1, 2, -1));
    });

    check_extrema(false, false);
  }

  @Test
  void extrema_of_diverging_resources() {
    setup(() -> {
      set(p, polynomial(0, 1, -1));
      set(q, polynomial(1, 2, 1));
    });

    check_extrema(false, false);
  }

  @Test
  void extrema_of_converging_resources() {
    setup(() -> {
      set(p, polynomial(0, 1, 1));
      set(q, polynomial(1, 2, -1));
    });

    check_extrema(false, true);
  }

  @Test
  void extrema_of_equal_then_diverging_resources() {
    setup(() -> {
      set(p, polynomial(1, 1, -1));
      set(q, polynomial(1, 2, 1));
    });

    check_extrema(false, false);
  }

  @Test
  void extrema_of_first_order_equal_then_diverging_resources() {
    setup(() -> {
      set(p, polynomial(1, 2, -1));
      set(q, polynomial(1, 2, 1));
    });

    check_extrema(false, false);
  }

  @Test
  void extrema_of_tangent_resources() {
    setup(() -> {
      set(p, polynomial(0, 2, -1));
      set(q, polynomial(2, -2, 1));
    });

    // No crossover because curves are tangent at t = 1, but q still dominates p
    check_extrema(false, false);
    // Explicitly check answer at t = 1, just to be sure:
    reset();
    delay(SECOND);
    check_extrema(false, false);
  }

  // "Fine precision":
  // Due to floating-point precision, it can take more than 1 microsecond
  // to actually change the value of a polynomial if the rates are sufficiently small.
  // Implementations of the comparisons and extrema must account for this
  // for simulations to be fast and stable.

  @Test
  void comparing_equal_then_diverging_linear_terms_with_fine_precision() {
    setup(() -> {
      set(p, polynomial(1000));
      set(q, polynomial(1000, -1e-20));
    });

    check_comparison(p_lt_q, false, false);
    check_comparison(p_lte_q, true, true);
    check_comparison(p_gt_q, false, true);
    check_comparison(p_gte_q, true, false);
    check_extrema(true, false);
  }

  @Test
  void comparing_converging_linear_terms_with_fine_precision() {
    setup(() -> {
      set(p, polynomial(1000 - 1e-6, 1e-14));
      set(q, polynomial(1000, -1e-12));
    });

    check_comparison(p_lt_q, true, true);
    check_comparison(p_lte_q, true, true);
    check_comparison(p_gt_q, false, true);
    check_comparison(p_gte_q, false, true);
    check_extrema(false, true);
  }

  @Test
  void comparing_equal_then_diverging_nonlinear_terms_with_fine_precision() {
    setup(() -> {
      set(p, polynomial(1000, -1e-20, 2e-22));
      set(q, polynomial(1000, -1e-20, 1e-22));
    });

    check_comparison(p_lt_q, false, false);
    check_comparison(p_lte_q, true, true);
    check_comparison(p_gt_q, false, true);
    check_comparison(p_gte_q, true, false);
    check_extrema(true, false);
  }

  @Test
  void comparing_converging_nonlinear_terms_with_fine_precision() {
    setup(() -> {
      set(p, polynomial(1000 - 1e-6, 1e-14, 1e20));
      set(q, polynomial(1000, -1e-12, -1e20));
    });

    check_comparison(p_lt_q, true, true);
    check_comparison(p_lte_q, true, true);
    check_comparison(p_gt_q, false, true);
    check_comparison(p_gte_q, false, true);
    check_extrema(false, true);
  }

  // Unrepresentable convergence:
  // These tests reflect polynomials that in theory converge, but do so in timespans
  // that are too large to represent. Thus, they should be treated as non-converging.

  @Test
  void comparing_linear_terms_with_convergence_unrepresentable_by_double() {
    setup(() -> {
      set(p, polynomial(Double.MAX_VALUE));
      set(q, polynomial(0, 0.1));
    });

    check_comparison(p_lt_q, false, false);
    check_comparison(p_lte_q, false, false);
    check_comparison(p_gt_q, true, false);
    check_comparison(p_gte_q, true, false);
    check_extrema(true, false);
  }

  @Test
  void comparing_linear_terms_with_convergence_unrepresentable_by_duration() {
    setup(() -> {
      set(p, polynomial(Duration.MAX_VALUE.ratioOver(SECOND)));
      set(q, polynomial(0, 0.1));
    });

    check_comparison(p_lt_q, false, false);
    check_comparison(p_lte_q, false, false);
    check_comparison(p_gt_q, true, false);
    check_comparison(p_gte_q, true, false);
    check_extrema(true, false);
  }

  @Test
  void comparing_nonlinear_terms_with_convergence_unrepresentable_by_double() {
    setup(() -> {
      set(p, polynomial(Double.MAX_VALUE));
      set(q, polynomial(0, 0, 0.1));
    });

    check_comparison(p_lt_q, false, false);
    check_comparison(p_lte_q, false, false);
    check_comparison(p_gt_q, true, false);
    check_comparison(p_gte_q, true, false);
    check_extrema(true, false);
  }

  @Test
  void comparing_nonlinear_terms_with_convergence_unrepresentable_by_duration() {
    setup(() -> {
      set(p, polynomial(Duration.MAX_VALUE.ratioOver(SECOND) * Duration.MAX_VALUE.ratioOver(SECOND)));
      set(q, polynomial(0, 0, 0.1));
    });

    check_comparison(p_lt_q, false, false);
    check_comparison(p_lte_q, false, false);
    check_comparison(p_gt_q, true, false);
    check_comparison(p_gte_q, true, false);
    check_extrema(true, false);
  }

  @Test
  void comparing_pathological_nonlinear_terms_with_convergence_unrepresentable_by_duration() {
    setup(() -> {
      set(p, polynomial(Duration.MAX_VALUE.ratioOver(SECOND) * Duration.MAX_VALUE.ratioOver(SECOND)));
      set(q, polynomial(0, Duration.MIN_VALUE.ratioOver(SECOND), 1.0 + Math.ulp(1.0)));
    });

    check_comparison(p_lt_q, false, false);
    check_comparison(p_lte_q, false, false);
    check_comparison(p_gt_q, true, false);
    check_comparison(p_gte_q, true, false);
    check_extrema(true, false);
  }

  private void check_comparison(Resource<Discrete<Boolean>> result, boolean expectedValue, boolean expectCrossover) {
    reset();
    var resultDynamics = result.getDynamics().getOrThrow();
    assertEquals(expectedValue, resultDynamics.data().extract());
    assertEquals(expectCrossover, !resultDynamics.expiry().isNever());
    if (expectCrossover) {
      Duration crossover = resultDynamics.expiry().value().get();
      delay(crossover.minus(EPSILON));
      assertEquals(expectedValue, currentValue(result));
      delay(EPSILON);
      assertEquals(!expectedValue, currentValue(result));
    }
  }

  private void check_extrema(boolean expect_p_dominates_q, boolean expectCrossover) {
    reset();

    var minPQDynamics = min_p_q.getDynamics();
    var minQPDynamics = min_q_p.getDynamics();
    var maxPQDynamics = max_p_q.getDynamics();
    var maxQPDynamics = max_q_p.getDynamics();

    // min and max are exactly symmetric
    assertEquals(minPQDynamics, minQPDynamics);
    assertEquals(maxPQDynamics, maxQPDynamics);

    // Expiry for min and max are exactly the same
    Expiry expiry = minPQDynamics.getOrThrow().expiry();
    assertEquals(expiry, maxPQDynamics.getOrThrow().expiry());
    // Expiry is finite iff we expect a crossover
    assertEquals(expectCrossover, !expiry.isNever());

    var expectedMax = expect_p_dominates_q ? p : q;
    var expectedMin = expect_p_dominates_q ? q : p;

    // Data for min and max match their corresponding arguments
    assertEquals(currentData(expectedMin), currentData(min_p_q));
    assertEquals(currentData(expectedMax), currentData(max_p_q));

    if (expectCrossover) {
      // Just before crossover, min and max still match their originally stated arguments
      delay(expiry.value().get().minus(EPSILON));
      assertEquals(currentData(expectedMin), currentData(min_p_q));
      assertEquals(currentData(expectedMax), currentData(max_p_q));
      // At crossover, min and max swap
      delay(EPSILON);
      assertEquals(currentData(expectedMax), currentData(min_p_q));
      assertEquals(currentData(expectedMin), currentData(max_p_q));
    }
  }

  // Helper utilities to reset the simulation during a test.
  // This is helpful to group similar test cases within a single method,
  // even though the simulation can advance while running assertions.
  private Runnable setupFunction = () -> {};
  private void setup(Runnable setupFunction) {
    this.setupFunction = setupFunction;
    reset();
  }
  private void reset() {
    setupFunction.run();
    delay(ZERO);
  }
}
