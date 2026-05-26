package gov.nasa.jpl.aerie.types;

public sealed interface ActivitySource<V> permits DirectiveActivitySource, ExternalEventActivitySource, ResourceActivitySource {
//  public ActivitySource<V> fromActivitySourceKt(Activ
  public V getValue();
}
