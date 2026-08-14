package gov.nasa.jpl.aerie.scheduler.worker;

import gov.nasa.jpl.aerie.scheduler.server.models.SpecificationId;

import java.util.Optional;
import java.util.function.Supplier;

public class SchedulingCanceledListener implements Supplier<Boolean> {
  private Optional<SpecificationId> registeredSchedulingRun;
  private boolean canceled;

  public SchedulingCanceledListener() {
    registeredSchedulingRun = Optional.empty();
    canceled = false;
  }

  /**
   * Receive a canceled signal.
   * All signals that are not for this object's registered scheduling run will be ignored.
   * @param payload The payload of the signal
   */
  public void receiveSignal(SpecificationId payload){
    if (registeredSchedulingRun.isEmpty() || !registeredSchedulingRun.get().equals(payload)) return;
    canceled = true;
  }

  /**
   * Register the listener to a specific scheduling run
   * @param id the specification id of the scheduling run
   */
  public void register(SpecificationId id) {
    registeredSchedulingRun = Optional.of(id);
    canceled = false;
  }

  /**
   * Unregister the listener
   */
  public void unregister(){
    registeredSchedulingRun = Optional.empty();
    canceled = false;
  }

  /**
   * @return if the current registered scheduling run has been canceled
   */
  public boolean isCanceled() {
    return registeredSchedulingRun.isPresent() && canceled;
  }

  @Override
  public Boolean get() {
    return isCanceled();
  }
}
