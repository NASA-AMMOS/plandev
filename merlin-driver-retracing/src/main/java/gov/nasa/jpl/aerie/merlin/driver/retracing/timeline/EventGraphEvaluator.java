package gov.nasa.jpl.aerie.merlin.driver.retracing.timeline;

import gov.nasa.jpl.aerie.merlin.protocol.model.EffectTrait;

import java.util.Optional;

public interface EventGraphEvaluator {
  <Effect> Optional<Effect> evaluate(EffectTrait<Effect> trait, Selector<Effect> selector, EventGraph<Event> graph);
}
