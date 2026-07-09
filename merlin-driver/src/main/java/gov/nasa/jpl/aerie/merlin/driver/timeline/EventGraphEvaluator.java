package gov.nasa.jpl.aerie.merlin.driver.timeline;

import gov.nasa.jpl.aerie.merlin.protocol.model.EffectTrait;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Optional;

public interface EventGraphEvaluator {
  <Effect> Pair<Optional<Effect>, Boolean> evaluate(EffectTrait<Effect> trait, Selector<Effect> selector, EventGraph<Event> graph,
                                                    final Event lastEvent, boolean includeLast);
}
