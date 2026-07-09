package gov.nasa.jpl.aerie.contrib.cells.durative;

import gov.nasa.jpl.aerie.contrib.traits.CommutativeMonoid;
import gov.nasa.jpl.aerie.merlin.framework.CellRef;
import gov.nasa.jpl.aerie.merlin.protocol.model.CellType;
import gov.nasa.jpl.aerie.merlin.protocol.model.EffectTrait;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.RealDynamics;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.function.Function;

public final class DurativeRealCell {
  private final PriorityQueue<Pair<Duration, RealDynamics>> activeEffects;
  private Duration elapsedTime;

  private DurativeRealCell(final PriorityQueue<Pair<Duration, RealDynamics>> activeEffects, final Duration elapsedTime) {
    this.activeEffects = new PriorityQueue<>(activeEffects);
    this.elapsedTime = Objects.requireNonNull(elapsedTime);
  }

  public DurativeRealCell() {
    this(new PriorityQueue<>(Comparator.comparing(Pair::getLeft)), Duration.ZERO);
  }

  public static <Event>
  CellRef<Event, DurativeRealCell> allocate(final Function<Event, Collection<Pair<Duration, RealDynamics>>> interpreter) {
    return CellRef.allocate(new DurativeRealCell(), new DurativeCellType(), interpreter);
  }

  public RealDynamics getValue() {
    var dynamics = RealDynamics.constant(0.0);

    for (final var entry : this.activeEffects) {
      dynamics = dynamics.plus(entry.getRight());
    }

    return dynamics;
  }

  @Override
  public String toString() {
    return "DurativeRealCell{" +
           "activeEffects=" + activeEffects +
           ", elapsedTime=" + elapsedTime +
           '}';
  }

  public static final class DurativeCellType
      implements CellType<Collection<Pair<Duration, RealDynamics>>, DurativeRealCell>
  {
    private static final EffectTrait<Collection<Pair<Duration, RealDynamics>>> monoid =
        new CommutativeMonoid<>(List.of(), ($1, $2) -> {
          if ($1.isEmpty()) return $2;
          if ($2.isEmpty()) return $1;

          final var $ = new ArrayList<Pair<Duration, RealDynamics>>($1.size() + $2.size());
          $.addAll($1);
          $.addAll($2);

          return $;
        });

    @Override
    public EffectTrait<Collection<Pair<Duration, RealDynamics>>> getEffectType() {
      return monoid;
    }

    @Override
    public DurativeRealCell duplicate(final DurativeRealCell cell) {
      return new DurativeRealCell(cell.activeEffects, cell.elapsedTime);
    }

    @Override
    public void apply(final DurativeRealCell cell, final Collection<Pair<Duration, RealDynamics>> effects) {
      for (final var effect : effects) {
        cell.activeEffects.add(Pair.of(
            cell.elapsedTime.plus(effect.getLeft()),
            effect.getRight()));
      }
    }

    @Override
    public void step(final DurativeRealCell cell, final Duration duration) {
      cell.elapsedTime = cell.elapsedTime.plus(duration);

      final var iter = cell.activeEffects.iterator();
      while (iter.hasNext()) {
        final var entry = iter.next();
        if (cell.elapsedTime.shorterThan(entry.getLeft())) break;
        iter.remove();
      }

      if (cell.activeEffects.isEmpty()) {
        cell.elapsedTime = Duration.ZERO;
      }
    }

    @Override
    public Optional<Duration> getExpiry(final DurativeRealCell cell) {
      if (cell.activeEffects.isEmpty()) return Optional.empty();

      return Optional.of(cell.activeEffects.peek().getLeft().minus(cell.elapsedTime));
    }
  }
}
