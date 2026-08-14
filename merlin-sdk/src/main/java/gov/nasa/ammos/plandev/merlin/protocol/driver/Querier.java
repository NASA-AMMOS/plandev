package gov.nasa.jpl.aerie.merlin.protocol.driver;

public interface Querier {
  <State> State getState(CellId<State> cellId);
}
