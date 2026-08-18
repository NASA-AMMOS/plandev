package gov.nasa.ammos.plandev.merlin.driver.timeline;

import gov.nasa.ammos.plandev.merlin.protocol.model.EffectTrait;

import java.util.Optional;

public interface EventGraphEvaluator {
  <Effect> Optional<Effect> evaluate(EffectTrait<Effect> trait, Selector<Effect> selector, EventGraph<Event> graph);
}
