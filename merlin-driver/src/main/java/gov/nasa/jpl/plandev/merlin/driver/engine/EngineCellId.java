package gov.nasa.jpl.plandev.merlin.driver.engine;

import gov.nasa.jpl.plandev.merlin.driver.timeline.Query;
import gov.nasa.jpl.plandev.merlin.protocol.driver.CellId;
import gov.nasa.jpl.plandev.merlin.protocol.driver.Topic;

public record EngineCellId<Event, State> (Topic<Event> topic, Query<State> query)
    implements CellId<State>
{}
