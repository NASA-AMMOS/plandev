package gov.nasa.ammos.plandev.merlin.protocol.model;

import gov.nasa.ammos.plandev.merlin.protocol.driver.Querier;

public interface Resource<Dynamics> {
  String getType();
  OutputType<Dynamics> getOutputType();

  /**
   * Get the current value of this resource.
   *
   * <p> The result of this method must vary only dependent on the cells allocated by the model instance that registered
   * this resource. In other words, it cannot depend on any hidden state. </p>
   */
  Dynamics getDynamics(Querier querier);
}
