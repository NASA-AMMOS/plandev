package gov.nasa.jpl.aerie.merlin.framework;

import gov.nasa.jpl.aerie.merlin.protocol.driver.CellId;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Topic;
import gov.nasa.jpl.aerie.merlin.protocol.model.CellType;
import gov.nasa.jpl.aerie.merlin.protocol.model.TaskFactory;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.InSpan;

import java.util.function.Function;

public interface Context {
  enum ContextType { Initializing, Reacting, Querying }

  // Usable in all contexts
  ContextType getContextType();

  // Usable during both initialization & simulation
  <State> State ask(CellId<State> cellId);

  // Usable during initialization
  <Event, Effect, State>
  CellId<State>
  allocate(
      State initialState,
      CellType<Effect, State> cellType,
      Function<Event, Effect> interpretation,
      Topic<Event> topic);

  // Usable during simulation
  <Event> void emit(Event event, Topic<Event> topic);

  void spawn(InSpan inSpan, TaskFactory<?> task);
  <Return> void call(InSpan inSpan, TaskFactory<Return> task);

  void delay(Duration duration);
  void waitUntil(Condition condition);
}
