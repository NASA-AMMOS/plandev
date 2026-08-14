package gov.nasa.jpl.aerie.merlin.driver.timeline;

import gov.nasa.jpl.aerie.merlin.protocol.model.EffectTrait;

import java.util.Optional;

public final class RecursiveEventGraphEvaluator implements EventGraphEvaluator {
  @Override
  public <Effect> Optional<Effect>
  evaluate(final EffectTrait<Effect> trait, final Selector<Effect> selector, final EventGraph<Event> graph) {
    if (graph instanceof EventGraph.Atom<Event> g) {
      return selector.select(trait, g.atom());
    } else if (graph instanceof EventGraph.Sequentially<Event> g) {
      var effect = evaluate(trait, selector, g.prefix());

      while (g.suffix() instanceof EventGraph.Sequentially<Event> rest) {
        effect = sequence(trait, effect, evaluate(trait, selector, rest.prefix()));
        g = rest;
      }

      return sequence(trait, effect, evaluate(trait, selector, g.suffix()));
    } else if (graph instanceof EventGraph.Concurrently<Event> g) {
      var effect = evaluate(trait, selector, g.right());

      while (g.left() instanceof EventGraph.Concurrently<Event> rest) {
        effect = merge(trait, evaluate(trait, selector, rest.right()), effect);
        g = rest;
      }

      return merge(trait, evaluate(trait, selector, g.left()), effect);
    } else if (graph instanceof EventGraph.Empty) {
      return Optional.empty();
    } else {
      throw new IllegalArgumentException();
    }
  }

  private static <Effect>
  Optional<Effect> sequence(final EffectTrait<Effect> trait, final Optional<Effect> a, final Optional<Effect> b) {
    if (a.isEmpty()) return b;
    if (b.isEmpty()) return a;

    return Optional.of(trait.sequentially(a.get(), b.get()));
  }

  private static <Effect>
  Optional<Effect> merge(final EffectTrait<Effect> trait, final Optional<Effect> a, final Optional<Effect> b) {
    if (a.isEmpty()) return b;
    if (b.isEmpty()) return a;

    return Optional.of(trait.concurrently(a.get(), b.get()));
  }
}
