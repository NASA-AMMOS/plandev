package gov.nasa.jpl.aerie.merlin.driver.timeline;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class LiveCells {
  // INVARIANT: Every Query<T> maps to a LiveCell<T>; that is, the type parameters are correlated.
  private final Map<Query<?>, LiveCell<?>> cells = new HashMap<>();
  private final EventSource source;
  private final LiveCells parent;

  public LiveCells(final EventSource source) {
    this.source = source;
    this.parent = null;
  }

  public LiveCells(final EventSource source, final LiveCells parent) {
    this.source = source;
    this.parent = parent;
  }

  public <State> Optional<State> getState(final Query<State> query) {
    return getCell(query).map(Cell::getState);
  }

  public Optional<Duration> getExpiry(final Query<?> query) {
    return getCell(query).flatMap(Cell::getExpiry);
  }

  public <State> void put(final Query<State> query, final Cell<State> cell) {
    // SAFETY: The query and cell share the same State type parameter.
    this.cells.put(query, new LiveCell<>(cell, this.source.cursor()));
  }

  private <State> Optional<Cell<State>> getCell(final Query<State> query) {
    // First, check if we have this cell already.
    {
      // SAFETY: By the invariant, if there is an entry for this query, it is of type Cell<State>.
      @SuppressWarnings("unchecked")
      final var cell = (LiveCell<State>) this.cells.get(query);

      if (cell != null) return Optional.of(cell.get());
    }

    // Otherwise, go ask our parent for the cell.
    if (this.parent == null) return Optional.empty();
    final var cell$ = this.parent.getCell(query);
    if (cell$.isEmpty()) return Optional.empty();

    final var cell = new LiveCell<>(cell$.get().duplicate(), this.source.cursor());

    // SAFETY: The query and cell share the same State type parameter.
    this.cells.put(query, cell);

    return Optional.of(cell.get());
  }

  public void freeze() {
    if (this.parent != null) this.parent.freeze();
    this.source.freeze();
  }
}
