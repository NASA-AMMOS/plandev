package gov.nasa.jpl.aerie.contrib.cells.durative;

import gov.nasa.jpl.aerie.merlin.protocol.model.EffectTrait;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

public class CollectingEffectTrait<T> implements EffectTrait<Collection<T>> {
  @Override
  public Collection<T> empty() {
    return Collections.emptyList();
  }

  @Override
  public Collection<T> sequentially(final Collection<T> prefix, final Collection<T> suffix) {
    return this.concurrently(prefix, suffix);
  }

  @Override
  public Collection<T> concurrently(final Collection<T> left, final Collection<T> right) {
    final var merged = new ArrayList<T>(left.size() + right.size());
    merged.addAll(left);
    merged.addAll(right);
    return merged;
  }
}
