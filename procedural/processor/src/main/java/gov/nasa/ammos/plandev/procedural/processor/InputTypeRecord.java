package gov.nasa.ammos.plandev.procedural.processor;

import javax.lang.model.element.TypeElement;
import java.util.List;

public record InputTypeRecord(
    String name,
    TypeElement declaration,
    List<ParameterRecord> parameters,
    MapperRecord mapper,
    ExportDefaultsStyle defaultsStyle
) {}
