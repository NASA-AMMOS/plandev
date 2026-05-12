package gov.nasa.ammos.aerie.procedural.processor;

import com.squareup.javapoet.ClassName;

import javax.lang.model.element.PackageElement;
import java.util.Objects;

public final class MapperRecord {
  public final ClassName name;

  public MapperRecord(final ClassName name) {
    this.name = Objects.requireNonNull(name);
  }

  public static MapperRecord
  generatedFor(final ClassName procedureTypeName, final PackageElement jarElement) {
    final var jarPackage = jarElement.getQualifiedName().toString();
    final var procedurePackage = procedureTypeName.packageName();

    final String generatedSuffix;
    if ((procedurePackage + ".").startsWith(jarPackage + ".")) {
      generatedSuffix = procedurePackage.substring(jarPackage.length());
    } else {
      generatedSuffix = procedurePackage;
    }

    final var mapperName = ClassName.get(
        jarPackage + ".generated" + generatedSuffix,
        procedureTypeName.simpleName());

    return new MapperRecord(mapperName);
  }
}
