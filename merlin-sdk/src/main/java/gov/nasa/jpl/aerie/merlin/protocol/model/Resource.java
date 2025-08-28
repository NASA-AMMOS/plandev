package gov.nasa.jpl.aerie.merlin.protocol.model;

import gov.nasa.jpl.aerie.merlin.protocol.driver.Querier;
import java.util.Optional;

public interface Resource<Dynamics> {
  String getType();

  /**
   * Get the description of this resource.
   * @return The description of this resource.
   */
  default Optional<String> getDescription() {
    return Optional.empty();
  }

  OutputType<Dynamics> getOutputType();

  /**
   * Get the current value of this resource.
   *
   * <p> The result of this method must vary only dependent on the cells allocated by the model instance that registered
   * this resource. In other words, it cannot depend on any hidden state. </p>
   */
  Dynamics getDynamics(Querier querier);
}
