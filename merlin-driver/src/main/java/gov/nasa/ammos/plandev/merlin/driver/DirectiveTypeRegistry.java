package gov.nasa.ammos.plandev.merlin.driver;

import gov.nasa.ammos.plandev.merlin.protocol.model.DirectiveType;
import gov.nasa.ammos.plandev.merlin.protocol.model.ModelType;

import java.util.Map;

public record DirectiveTypeRegistry<Model>(Map<String, ? extends DirectiveType<Model, ?, ?>> directiveTypes) {
  public static <Model>
  DirectiveTypeRegistry<Model> extract(final ModelType<?, Model> modelType) {
    return new DirectiveTypeRegistry<>(modelType.getDirectiveTypes());
  }
}
