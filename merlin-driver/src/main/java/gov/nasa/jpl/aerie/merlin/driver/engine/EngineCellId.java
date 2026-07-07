package gov.nasa.jpl.aerie.merlin.driver.engine;

import gov.nasa.jpl.aerie.merlin.driver.timeline.Query;
import gov.nasa.ammos.plandev.merlin.protocol.driver.CellId;
import gov.nasa.ammos.plandev.merlin.protocol.driver.Topic;

public record EngineCellId<Event, State> (Topic<Event> topic, Query<State> query)
    implements CellId<State>, gov.nasa.jpl.aerie.merlin.protocol.driver.CellId<State>
{}
