package gov.nasa.ammos.plandev.merlin.processor.metamodel;

import gov.nasa.ammos.plandev.merlin.framework.annotations.ActivityType;

import javax.lang.model.type.TypeMirror;
import java.util.Optional;

public record EffectModelRecord(
    String methodName,
    ActivityType.Executor executor,
    Optional<TypeMirror> returnType,
    Optional<String> durationParameter,
    Optional<String> fixedDurationExpr,
    Optional<String> parametricDuration,
    Optional<String> maximumDuration
) {
}
