package gov.nasa.jpl.aerie.merlin.framework;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static gov.nasa.jpl.aerie.merlin.framework.Condition.*;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.*;
import static org.junit.jupiter.api.Assertions.*;

class ConditionTest {
  @Test
  @DisplayName("TRUE is always satisfied")
  public void trueSatisfiedAtEarliest() {
    assertEquals(Optional.of(MINUTE), TRUE.nextSatisfied(true, MINUTE, HOUR));
  }

  @Test
  @DisplayName("TRUE is never unsatisfied")
  public void trueNeverUnsatisfied() {
    assertEquals(Optional.empty(), TRUE.nextSatisfied(false, MINUTE, HOUR));
  }

  @Test
  @DisplayName("FALSE is never satisfied")
  public void falseNeverSatisfied() {
    assertEquals(Optional.empty(), FALSE.nextSatisfied(true, MINUTE, HOUR));
  }

  @Test
  @DisplayName("FALSE is always unsatisfied")
  public void falseUnsatisfiedAtEarliest() {
    assertEquals(Optional.of(MINUTE), FALSE.nextSatisfied(false, MINUTE, HOUR));
  }

  @Test
  @DisplayName("Disjunction unsatisfied when neither condition satisfied")
  public void disjunctionUnsatisfiedByEitherOperand() {
    assertEquals(Optional.empty(), or(FALSE, FALSE).nextSatisfied(true, MINUTE, HOUR));
  }

  @Test
  @DisplayName("Disjunction satisfied when either condition satisfied")
  public void disjunctionSatisfiedByEitherOperand() {
    assertEquals(Optional.of(MINUTE), or(TRUE, FALSE).nextSatisfied(true, MINUTE, HOUR));
    assertEquals(Optional.of(MINUTE), or(FALSE, TRUE).nextSatisfied(true, MINUTE, HOUR));
  }

  @Test
  @DisplayName("Disjunction satisfied when both conditions are satisfied")
  public void disjunctionSatisfiedByBothOperands() {
    Condition c1 = window(MINUTE.times(2), MINUTE.times(30));
    Condition c2 = window(MINUTE.times(5), HOUR);

    assertEquals(Optional.of(MINUTE.times(2)), or(c1, c2).nextSatisfied(true, MINUTE, HOUR));
    assertEquals(Optional.of(MINUTE.times(2)), or(c2, c1).nextSatisfied(true, MINUTE, HOUR));
  }

  @Test
  @DisplayName("Conjunction dissatisfied when neither condition satisfied")
  public void conjunctionUnsatisfiedByEitherOperand() {
    assertEquals(Optional.empty(), and(FALSE, FALSE).nextSatisfied(true, MINUTE, HOUR));
  }

  @Test
  @DisplayName("Conjunction dissatisfied when either condition unsatisfied")
  public void conjunctionUnsatisfiedByEitherOperandIndividually() {
    assertEquals(Optional.empty(), and(FALSE, TRUE).nextSatisfied(true, MINUTE, HOUR));
    assertEquals(Optional.empty(), and(TRUE, FALSE).nextSatisfied(true, MINUTE, HOUR));
  }

  @Test
  @DisplayName("Conjunction satisfied when both operands are simultaneously satisfied")
  public void conjunctionSatisfiedByBothOperands() {
    Condition c1 = window(MINUTE.times(2), MINUTE.times(30));
    Condition c2 = window(MINUTE.times(5), HOUR);

    assertEquals(Optional.of(MINUTE.times(5)), and(c1, c2).nextSatisfied(true, MINUTE, HOUR));
    assertEquals(Optional.of(MINUTE.times(5)), and(c2, c1).nextSatisfied(true, MINUTE, HOUR));
  }

  @Test
  @DisplayName("Conjunction dissatisfied when operands are non-simultaneously satisfied")
  public void conjunctionDissatisfiedByDifferentlyTimedOperands() {
    Condition c1 = window(MINUTE.times(2), MINUTE.times(30));
    Condition c2 = window(MINUTE.times(31), HOUR);

    assertEquals(Optional.empty(), and(c1, c2).nextSatisfied(true, MINUTE, HOUR));
    assertEquals(Optional.empty(), and(c2, c1).nextSatisfied(true, MINUTE, HOUR));
  }

  @Test
  @DisplayName("Not operation uses negation flag")
  public void notCondition() {
    Condition c = window(MINUTE.times(2), MINUTE.times(5));
    assertEquals(Optional.of(MINUTE), not(c).nextSatisfied(true, MINUTE, HOUR));
    assertEquals(Optional.of(MINUTE.times(5)), not(c).nextSatisfied(true, MINUTE.times(3), HOUR));
    assertEquals(Optional.empty(), not(c).nextSatisfied(true, MINUTE.times(3), MINUTE.times(4)));
    assertEquals(Optional.of(MINUTE.times(2)), not(c).nextSatisfied(false, MINUTE, HOUR));
    assertEquals(Optional.of(MINUTE.times(3)), not(c).nextSatisfied(false, MINUTE.times(3), HOUR));
    assertEquals(Optional.of(MINUTE.times(3)), not(c).nextSatisfied(false, MINUTE.times(3), MINUTE.times(4)));
  }

  @Test
  @DisplayName("Negated disjunction satisfied when both conditions are unsatisfied")
  public void negatedDisjunctionSatisfiedByBothOperands() {
    Condition c1 = window(MINUTE, MINUTE.times(20));
    Condition c2 = window(MINUTE.times(5), MINUTE.times(30));

    assertEquals(Optional.of(MINUTE.times(30)), not(or(c1, c2)).nextSatisfied(true, MINUTE, HOUR));
    assertEquals(Optional.of(MINUTE.times(30)), not(or(c2, c1)).nextSatisfied(true, MINUTE, HOUR));
  }

  @Test
  @DisplayName("Negated disjunction unsatisfied when first condition satisfied")
  public void negatedDisjunctionUnsatisfiedByFirstOperand() {
    assertEquals(Optional.empty(), not(or(TRUE, FALSE)).nextSatisfied(true, MINUTE, HOUR));
  }

  @Test
  @DisplayName("Negated disjunction unsatisfied when second condition satisfied")
  public void negatedDisjunctionUnsatisfiedBySecondOperand() {
    assertEquals(Optional.empty(), not(or(FALSE, TRUE)).nextSatisfied(true, MINUTE, HOUR));
  }

  @Test
  @DisplayName("Negated disjunction unsatisfied when both conditions are satisfied")
  public void negatedDisjunctionUnsatisfiedByBothOperands() {
    assertEquals(Optional.empty(), not(or(TRUE, TRUE)).nextSatisfied(true, MINUTE, HOUR));
  }

  @Test
  @DisplayName("Negated conjunction satisfied when neither condition satisfied")
  public void negatedConjunctionSatisfiedByBothOperands() {
    assertEquals(Optional.of(MINUTE), not(and(FALSE, FALSE)).nextSatisfied(true, MINUTE, HOUR));
  }

  @Test
  @DisplayName("Negated conjunction satisfied when either condition unsatisfied")
  public void negatedConjunctionSatisfiedByEitherOperand() {
    Condition c1 = window(MINUTE, MINUTE.times(30));
    Condition c2 = window(MINUTE, HOUR);

    assertEquals(Optional.of(MINUTE.times(30)), not(and(c1, c2)).nextSatisfied(true, MINUTE, HOUR));
    assertEquals(Optional.of(MINUTE.times(30)), not(and(c2, c1)).nextSatisfied(true, MINUTE, HOUR));
  }

  @Test
  @DisplayName("Negated conjunction unsatisfied when both operands are simultaneously satisfied")
  public void negatedConjunctionUnsatisfiedByBothOperands() {
    assertEquals(Optional.empty(), not(and(TRUE, TRUE)).nextSatisfied(true, MINUTE, HOUR));
  }

  /**
   * Returns a condition that is satisfied in the half-open interval [start, end)
   */
  private static Condition window(Duration start, Duration end) {
    // Explanation:
    //   Positive condition:
    //     Candidate time to be satisfied is start if atEarliest is before [start, end), or atEarliest itself.
    //     This candidate is acceptable if it's before end (strictly) and before atLatest (non-strictly).
    //   Negative condition:
    //     Not satisfied atEarliest, provided this is before start (strictly).
    //     Otherwise, satisfied at end or atEarliest, provided this is before atLatest (non-strictly).
    return ((positive, atEarliest, atLatest) -> positive
        ? Optional.of(max(atEarliest, start)).filter(end::longerThan).filter(atLatest::noShorterThan)
        : Optional.of(atEarliest).filter(start::longerThan)
                  .or(() -> Optional.of(max(atEarliest, end)).filter(atLatest::noShorterThan)));
  }
}
