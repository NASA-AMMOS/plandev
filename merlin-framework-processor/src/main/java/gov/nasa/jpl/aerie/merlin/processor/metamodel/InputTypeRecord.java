package gov.nasa.jpl.aerie.merlin.processor.metamodel;

import javax.lang.model.element.TypeElement;
import java.util.List;

public record InputTypeRecord(
    String name,
    TypeElement declaration,
    List<ParameterRecord> parameters,
    List<ParameterValidationRecord> validations,
    ActivityMapperRecord activityMapper,
    ActivityValueMapperRecord valueMapper,
    ExportDefaultsStyle defaultsStyle
) {}
