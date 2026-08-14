package gov.nasa.ammos.plandev.merlin.processor.metamodel;

import com.squareup.javapoet.TypeName;
import gov.nasa.ammos.plandev.merlin.protocol.types.Unit;

import java.util.Optional;

public record ActivityTypeRecord(
    String fullyQualifiedClass,
    String name,
    Optional<String> subsystem,
    Optional<String> description,
    InputTypeRecord inputType,
    Optional<EffectModelRecord> effectModel
) {
  public TypeName getOutputTypeName() {
    return this.effectModel
        .flatMap(EffectModelRecord::returnType)
        .map(TypeName::get)
        .map(TypeName::box)
        .orElseGet(() -> TypeName.get(Unit.class));
  }
}
