package gov.nasa.jpl.plandev.merlin.protocol.driver;

public interface Querier {
  <State> State getState(CellId<State> cellId);
}
