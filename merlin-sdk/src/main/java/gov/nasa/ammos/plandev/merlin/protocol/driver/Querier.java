package gov.nasa.ammos.plandev.merlin.protocol.driver;

public interface Querier {
  <State> State getState(CellId<State> cellId);
}
