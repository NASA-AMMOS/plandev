package gov.nasa.jpl.aerie.merlin.framework;

import gov.nasa.jpl.aerie.merlin.protocol.driver.CellId;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Topic;
import gov.nasa.jpl.aerie.merlin.protocol.model.CellType;

import java.util.function.Function;

public final class CellRef<Event, State> {
  public final Topic<Event> topic;
  public final CellId<State> cellId;

  private CellRef(final Topic<Event> topic, final CellId<State> cellId) {
    this.topic = topic;
    this.cellId = cellId;
  }

  public static <Event, Effect, State>
  CellRef<Event, State> allocate(
      final State initialState,
      final CellType<Effect, State> cellType,
      final Function<Event, Effect> eventToEffect,
      final Topic<Event> topic
  ) {
    final var cellId = ModelActions.context.get().allocate(initialState, cellType, eventToEffect, topic);

    return new CellRef<>(topic, cellId);
  }

  public static <Event, Effect, State>
  CellRef<Event, State> allocate(
      final State initialState,
      final CellType<Effect, State> cellType,
      final Function<Event, Effect> eventToEffect
  ) {
    return CellRef.allocate(initialState, cellType, eventToEffect, new Topic<>());
  }

  public static <Effect, State>
  CellRef<Effect, State> allocate(final State initialState, final CellType<Effect, State> cellType) {
    return allocate(initialState, cellType, $ -> $);
  }

  public State get() {
    return ModelActions.context.get().ask(this.cellId);
  }

  public void emit(final Event event) {
    ModelActions.context.get().emit(event, this.topic);
  }
}
