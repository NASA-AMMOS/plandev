package gov.nasa.jpl.aerie.merlin.protocol.driver;

import gov.nasa.jpl.aerie.merlin.protocol.model.TaskFactory;
import gov.nasa.jpl.aerie.merlin.protocol.types.InSpan;

public interface Scheduler {
  <State> State get(CellId<State> cellId);

  <Event> void emit(Event event, Topic<Event> topic);

  void spawn(InSpan taskSpan, TaskFactory<?> task);
  <T> void startActivity(T activity, Topic<T> inputTopic);
  <T> void endActivity(T result, Topic<T> outputTopic);
  default <ActivityDirectiveId> void startDirective(ActivityDirectiveId directiveId, Topic<ActivityDirectiveId> activityTopic) {}
}
