package gov.nasa.jpl.aerie.merlin.driver.timeline;

import gov.nasa.jpl.aerie.merlin.protocol.model.EffectTrait;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.Optional;

public final class RecursiveEventGraphEvaluator implements EventGraphEvaluator {
  public enum EvalState { DURING, AFTER }  // used to include BEFORE

  public EvalState evaluating = EvalState.DURING;

  /**
   * Compute the effect produced by selected events from an EventGraph as specific by an EffectTrait
   * @param trait specification of how to compute the effect of a partial order of Events
   * @param selector selects what Events are combined
   * @param graph the EventGraph to evaluate
   * @param lastEvent early termination point in the graph; no early termination for a null value or an Event not in the graph
   * @param includeLast whether to include lastEvent in the evaluation
   * @return the Effect resulting from evaluating the EventGraph and whether lastEvent was encountered
   * @param <Effect> the class/interface of the object computed by the EffectTrait
   */
  @Override
  public <Effect> Pair<Optional<Effect>, Boolean>
  evaluate(final EffectTrait<Effect> trait, final Selector<Effect> selector, final EventGraph<Event> graph,
           final Event lastEvent, final boolean includeLast) {
    evaluating = EvalState.DURING; // TODO -- now that
    return evaluateR(trait, selector, graph, lastEvent, includeLast);
  }
  public <Effect> Pair<Optional<Effect>, Boolean>
  evaluateR(final EffectTrait<Effect> trait, final Selector<Effect> selector, final EventGraph<Event> graph,
           final Event lastEvent, final boolean includeLast) {
    // Make sure we don't bother evaluating after finding the last event -- this shouldn't happen; maybe remove
    if (evaluating == EvalState.AFTER) return Pair.of(Optional.empty(), true);

    // case graph is Atom
    if (graph instanceof EventGraph.Atom<Event> g) {
      if (lastEvent != null && lastEvent.equals(g.atom())) {
        evaluating = EvalState.AFTER;
        if (!includeLast) {
          return Pair.of(Optional.empty(), true);
        }
      }
      return Pair.of(selector.select(trait, g.atom()), false);

    // case graph is Sequentially
    } else if (graph instanceof EventGraph.Sequentially<Event> g) {
      var result1 = evaluateR(trait, selector, g.prefix(), lastEvent, includeLast);
      var effect = result1.getLeft();
      while (evaluating != EvalState.AFTER && g.suffix() instanceof EventGraph.Sequentially<Event> rest) {
        var result2 = evaluate(trait, selector, rest.prefix(), lastEvent, includeLast);
        var effect2 = result2.getLeft();
        effect = sequence(trait, effect, effect2);
        g = rest;
      }
      if (evaluating == EvalState.AFTER) return Pair.of(effect, true);
      result1 = evaluateR(trait, selector, g.suffix(), lastEvent, includeLast);
      var effect3 = result1.getLeft();
      return Pair.of(sequence(trait, effect, effect3), result1.getRight());

    // case graph is Concurrently
    } else if (graph instanceof EventGraph.Concurrently<Event> g) {
      var concurrentGraphs = new ArrayList<EventGraph<Event>>();
      var concurrentEffects = new ArrayList<Optional<Effect>>();

      // gather concurrent branches
      concurrentGraphs.add(g.right());
      while (g.left() instanceof EventGraph.Concurrently<Event> rest) {
        concurrentGraphs.add(rest.right());
        g = rest;
      }
      concurrentGraphs.add(g.left());

      // gather effects of each branch, but if found last event, go ahead and return the Effect of that branch
      for (EventGraph<Event> cg : concurrentGraphs) {
        var result = evaluateR(trait, selector, cg, lastEvent, includeLast);
        Optional<Effect> effect = result.getLeft();
        // only need the effect from the branch where evaluation terminated
        if (evaluating == EvalState.AFTER) {
          return Pair.of(effect, true);
        }
        concurrentEffects.add(effect);
      }

      // combine effects across all evaluated branches
      Optional<Effect> effect = Optional.empty();
      for (Optional<Effect> eff : concurrentEffects) {
        effect = merge(trait, eff, effect);
      }
      return Pair.of(effect, evaluating == EvalState.AFTER);

    // case graph is Empty
    } else if (graph instanceof EventGraph.Empty) {
      return Pair.of(Optional.empty(), evaluating == EvalState.AFTER);
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
