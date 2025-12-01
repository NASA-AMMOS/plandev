package gov.nasa.jpl.plandev.merlin.driver.timeline;

import gov.nasa.jpl.plandev.merlin.protocol.model.EffectTrait;

import java.util.Optional;

public interface EventGraphEvaluator {
  <Effect> Optional<Effect> evaluate(EffectTrait<Effect> trait, Selector<Effect> selector, EventGraph<Event> graph);
}
