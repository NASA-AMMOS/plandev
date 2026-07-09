package gov.nasa.jpl.aerie.merlin.driver.timeline;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

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
    final var cell = getCell(query);
    return cell != null ? Optional.of(cell.getState()) : Optional.empty();
  }

  public Optional<Duration> getExpiry(final Query<?> query) {
    final var cell = getCell(query);
    if (cell == null) return Optional.empty();
    return cell.getExpiry();
  }

  public <State> void put(final Query<State> query, final Cell<State> cell) {
    // SAFETY: The query and cell share the same State type parameter.
    this.cells.put(query, new LiveCell<>(cell, this.source.cursor()));
  }

  /**
   * Returns the cell for the given query, or null if not found.
   * Private method — callers wrap in Optional for the public API.
   */
  private <State> Cell<State> getCell(final Query<State> query) {
    // First, check if we have this cell already.
    {
      // SAFETY: By the invariant, if there is an entry for this query, it is of type Cell<State>.
      @SuppressWarnings("unchecked")
      final var cell = (LiveCell<State>) this.cells.get(query);

      if (cell != null) return cell.get();
    }

    // Otherwise, go ask our parent for the cell.
    if (this.parent == null) return null;
    final var parentCell = this.parent.getCell(query);
    if (parentCell == null) return null;

    final var cell = new LiveCell<>(parentCell.duplicate(), this.source.cursor());

    // SAFETY: The query and cell share the same State type parameter.
    this.cells.put(query, cell);

    return cell.get();
  }

  public void freeze() {
    if (this.parent != null) this.parent.freeze();
    this.source.freeze();
  }

  private Stream<Query<?>> allQueriesStream() {
      var myStream = this.cells.keySet().stream();
      if (this.parent == null) return myStream;
      return Stream.concat(this.parent.allQueriesStream(), myStream);
  }

  public void stepUpAll() {
      allQueriesStream().forEach(this::getCell);
  }
}
