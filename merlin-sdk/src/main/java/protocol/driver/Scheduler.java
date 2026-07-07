package protocol.driver;

import protocol.model.TaskFactory;
import protocol.types.InSpan;

public interface Scheduler {
  <State> State get(CellId<State> cellId);

  <Event> void emit(Event event, Topic<Event> topic);

  void spawn(InSpan taskSpan, TaskFactory<?> task);
}
