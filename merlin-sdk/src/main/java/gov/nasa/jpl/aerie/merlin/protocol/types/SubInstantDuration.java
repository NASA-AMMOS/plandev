package gov.nasa.jpl.aerie.merlin.protocol.types;

/**
 * A {@link Duration} (interpreted as a time offset) paired with an index into a sequence of events occurring within
 * that atomic time value.
 * @param duration
 * @param index
 */
public record SubInstantDuration(Duration duration, Integer index) implements Comparable<SubInstantDuration> {

  public static SubInstantDuration ZERO = new SubInstantDuration(Duration.ZERO, 0);
  public static SubInstantDuration MAX_VALUE = new SubInstantDuration(Duration.MAX_VALUE, Integer.MAX_VALUE);
  public static SubInstantDuration MIN_VALUE = new SubInstantDuration(Duration.MIN_VALUE, 0);
  public static SubInstantDuration EPSILON = new SubInstantDuration(Duration.EPSILON, 0);
  public static SubInstantDuration EPSILONI = new SubInstantDuration(Duration.ZERO, 1);

  /**
   * Compares this object with the specified object for order.  Returns a
   * negative integer, zero, or a positive integer as this object is less
   * than, equal to, or greater than the specified object.
   *
   * <p>The implementor must ensure {@link Integer#signum
   * signum}{@code (x.compareTo(y)) == -signum(y.compareTo(x))} for
   * all {@code x} and {@code y}.  (This implies that {@code
   * x.compareTo(y)} must throw an exception if and only if {@code
   * y.compareTo(x)} throws an exception.)
   *
   * <p>The implementor must also ensure that the relation is transitive:
   * {@code (x.compareTo(y) > 0 && y.compareTo(z) > 0)} implies
   * {@code x.compareTo(z) > 0}.
   *
   * <p>Finally, the implementor must ensure that {@code
   * x.compareTo(y)==0} implies that {@code signum(x.compareTo(z))
   * == signum(y.compareTo(z))}, for all {@code z}.
   *
   * @param o the object to be compared.
   * @return a negative integer, zero, or a positive integer as this object
   * is less than, equal to, or greater than the specified object.
   * @throws NullPointerException if the specified object is null
   * @throws ClassCastException   if the specified object's type prevents it
   *                              from being compared to this object.
   * @apiNote It is strongly recommended, but <i>not</i> strictly required that
   * {@code (x.compareTo(y)==0) == (x.equals(y))}.  Generally speaking, any
   * class that implements the {@code Comparable} interface and violates
   * this condition should clearly indicate this fact.  The recommended
   * language is "Note: this class has a natural ordering that is
   * inconsistent with equals."
   */
  @Override
  public int compareTo(final SubInstantDuration o) {
    int r = this.duration.compareTo(o.duration);
    if (r != 0) return r;
    r = Integer.compare(this.index, o.index);
    return r;
  }

  public int compareTo(final Duration o) {
    return this.duration.compareTo(o);
  }

  public boolean isEqualTo(SubInstantDuration o) {
    return this.duration.isEqualTo(o.duration) && this.index == o.index;
  }

  public boolean isEqualTo(Duration o) {
    return this.duration.isEqualTo(o);
  }

  public boolean longerThan(final SubInstantDuration o) {
    return this.compareTo(o) > 0;
  }

  public boolean longerThan(final Duration o) {
    return this.compareTo(o) > 0;
  }

  public boolean noLongerThan(final SubInstantDuration o) {
    return this.compareTo(o) <= 0;
  }

  public boolean noLongerThan(final Duration o) {
    return this.compareTo(o) <= 0;
  }

  public boolean shorterThan(final SubInstantDuration o) {
    return this.compareTo(o) < 0;
  }

  public boolean shorterThan(final Duration o) {
    return this.compareTo(o) < 0;
  }

  public boolean noShorterThan(final SubInstantDuration o) {
    return this.compareTo(o) >= 0;
  }

  public boolean noShorterThan(final Duration o) {
    return this.compareTo(o) >= 0;
  }


  public static SubInstantDuration min(SubInstantDuration d1, SubInstantDuration d2) {
    return d1.longerThan(d2) ? d2 : d1;
  }

  public static SubInstantDuration max(SubInstantDuration d1, SubInstantDuration d2) {
    return d1.shorterThan(d2) ? d2 : d1;
  }

  // TODO: Should handle Integer.MIN_VALUE and negative this.index
  // TODO: Enforce index >= 0 or else isEqualTo() should check for index < 0
  // TODO: REVIEW -- Should Integer.MAX_VALUE be considered Inf, in which case Integer.MAX_VALUE == Integer.MAX_VALUE + 1?
  // TODO: Should handle Duration.MIN_VALUE

  public SubInstantDuration plus(SubInstantDuration d) {
    if (d.index < 0) {
      return this.plus(d.duration).plus(-d.index);
    }
    var newDuration = duration.plus(d.duration);
    if (Integer.MAX_VALUE - index < d.index) {
      if (newDuration.isEqualTo(Duration.MAX_VALUE)) {
        return MAX_VALUE;
      }
      return new SubInstantDuration(newDuration.plus(Duration.EPSILON), (index - Integer.MAX_VALUE) + d.index);
    }
    return new SubInstantDuration(duration.plus(d.duration), index + d.index);
  }
  public SubInstantDuration plus(Duration d) {
    return new SubInstantDuration(duration.plus(d), index);
  }
  public SubInstantDuration plus(Integer i) {
    if (i < 0) {
      return this.minus(-i);
    }
    if (Integer.MAX_VALUE - index < i) {
      if (duration.isEqualTo(Duration.MAX_VALUE)) {
        return MAX_VALUE;
      }
      return new SubInstantDuration(duration.plus(Duration.EPSILON), (index - Integer.MAX_VALUE) + i);
    }
    return new SubInstantDuration(duration, index + i);
  }
  public SubInstantDuration minus(SubInstantDuration d) {
    if (d.index < 0) {
      return this.minus(d.duration).plus(-d.index);
    }
    var newDuration = duration.minus(d.duration);
    if (index - d.index < 0) {
      if (newDuration.isEqualTo(Duration.MIN_VALUE)) {
        return MIN_VALUE;
      }
      return new SubInstantDuration(newDuration.minus(Duration.EPSILON), Integer.MAX_VALUE + (index - d.index));
    }
    return new SubInstantDuration(newDuration, index - d.index);
  }
  public SubInstantDuration minus(Duration d) {
    return new SubInstantDuration(duration.minus(d), index);
  }
  public SubInstantDuration minus(Integer i) {
    if (i < 0) {
      return this.plus(-i);
    }
    if (index - i < 0) {
      if (duration.isEqualTo(Duration.MIN_VALUE)) {
        return MIN_VALUE;
      }
      //System.out.println(this + " - " + i + " = SubInstantDuration(" + duration.minus(Duration.EPSILON) + ", " + Integer.MAX_VALUE + (index - i) + ")");
      return new SubInstantDuration(duration.minus(Duration.EPSILON), Integer.MAX_VALUE + (index - i));
    }
    return new SubInstantDuration(duration, index - i);
  }
}
