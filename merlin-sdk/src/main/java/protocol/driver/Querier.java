package protocol.driver;

public interface Querier {
  <State> State getState(CellId<State> cellId);
}
