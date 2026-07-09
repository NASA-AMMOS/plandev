package gov.nasa.jpl.aerie.merlin.driver.retracing.engine;

import gov.nasa.jpl.aerie.merlin.driver.retracing.timeline.Query;
import gov.nasa.jpl.aerie.merlin.protocol.driver.CellId;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Topic;

public record EngineCellId<Event, State> (Topic<Event> topic, Query<State> query)
    implements CellId<State>
{}
