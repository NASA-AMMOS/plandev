package gov.nasa.jpl.aerie.types;

import org.apache.commons.lang3.tuple.Pair;

public final class DirectiveActivitySource implements ActivitySource<Pair<ActivityDirective, Long>> {
  Pair<ActivityDirective, Long> value;

  public DirectiveActivitySource(ActivityDirective directive, Long directiveId) {
    value = Pair.of(directive, directiveId);
  }

  @Override
  public Pair<ActivityDirective, Long> getValue() {
    return value;
  }
}
