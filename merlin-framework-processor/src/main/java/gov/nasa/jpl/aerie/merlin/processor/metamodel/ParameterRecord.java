package gov.nasa.jpl.aerie.merlin.processor.metamodel;

import javax.lang.model.element.Element;
import javax.lang.model.type.TypeMirror;
import java.util.Objects;
import java.util.Optional;

public final class ParameterRecord {
  public final String name;
  public final TypeMirror type;
  public final Element element;
  public final Optional<String> description;

  public ParameterRecord(final String name, final TypeMirror type, final Element element, final Optional<String> description) {
    this.name = Objects.requireNonNull(name);
    this.type = Objects.requireNonNull(type);
    this.element = Objects.requireNonNull(element);
    this.description = Objects.requireNonNull(description);
  }
}
