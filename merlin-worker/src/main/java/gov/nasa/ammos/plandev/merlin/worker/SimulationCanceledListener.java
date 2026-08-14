package gov.nasa.jpl.aerie.merlin.worker;

import gov.nasa.jpl.aerie.merlin.server.models.DatasetId;

import java.util.Optional;
import java.util.function.Supplier;

public class SimulationCanceledListener implements Supplier<Boolean> {
  private Optional<DatasetId> registeredSimulation;
  private boolean canceled;

  public SimulationCanceledListener() {
    registeredSimulation = Optional.empty();
    canceled = false;
  }

  /**
   * Receive a canceled signal.
   * All signals that are not for this object's registered simulation will be ignored.
   * @param payload The payload of the signal
   */
  public void receiveSignal(DatasetId payload){
    if (registeredSimulation.isEmpty() || !registeredSimulation.get().equals(payload)) return;
    canceled = true;
  }

  /**
   * Register the listener to a specific simulation run
   * @param id the id of the simulation run
   */
  public void register(DatasetId id) {
    registeredSimulation = Optional.of(id);
    canceled = false;
  }

  /**
   * Unregister the listener
   */
  public void unregister(){
    registeredSimulation = Optional.empty();
    canceled = false;
  }

  /**
   * @return if the current registered simulation has been canceled
   */
  public boolean isCanceled() {
    return registeredSimulation.isPresent() && canceled;
  }

  @Override
  public Boolean get() {
    return isCanceled();
  }
}
