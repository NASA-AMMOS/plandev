package gov.nasa.jpl.aerie.constraints;

import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class TypescriptCodeGenerationService {

  private static final ValueSchema LINEAR_RESOURCE_SCHEMA = ValueSchema.ofStruct(Map.of(
      "initial", ValueSchema.REAL,
      "rate", ValueSchema.REAL));

  public record Parameter(String name, ValueSchema schema) {}
  public record ActivityType(List<Parameter> parameters) {}

  public static String generateTypescriptTypes(
      final Map<String, ActivityType> activityTypes,
      final Map<String, ValueSchema> resources
  ) {
    final var result = new ArrayList<String>();
    result.add("/** Start Codegen */");
    result.add("import * as AST from './constraints-ast.js';");
    result.add("import { Discrete, Real, Windows, ActivityInstance } from './constraints-edsl-fluent-api.js';");

    result.add("export enum ActivityType {");
    for (String activityType: activityTypes.keySet()) {
      result.add(indent(activityType + " = \"" + activityType + "\","));
    }
    result.add("}");

    result.add("export type Resource = {");
    for (var resource: resources.keySet()) {
      result.add(indent("\"" + resource + "\": " + valueSchemaToTypescript(resources.get(resource)) + ","));
    }
    result.add("};");

    result.add("export type ResourceName = " + String.join(
        " | ",
        Stream.concat(resources.keySet().stream().map(key -> ("\"" + key + "\"")), Stream.of("never")).toList()
    ) + ";");

    result.add("export type RealResourceName = " + String.join(
        " | ",
        Stream.concat(
            resources.entrySet().stream().filter($ -> valueSchemaIsNumeric($.getValue())).map($ -> ("\"" + $.getKey() + "\"")),
            Stream.of("never"))
              .toList()
    ) + ";");

    // ActivityParameters

    result.add("export const ActivityTypeParameterInstantiationMap = {");
    for (String activityType: activityTypes.keySet()) {
      result.add(indent("[ActivityType." + activityType + "]: (alias: string) => ({"));
      for (final var parameter: activityTypes.get(activityType).parameters()) {
        var parameterProfile = valueSchemaToTypescriptProfile(parameter.schema());
        final String nodeKind;
        if (parameterProfile.equals("Real")) {
          nodeKind = "RealProfileParameter";
        } else {
          nodeKind = "DiscreteProfileParameter";
        }
        result.add(indent(indent("\"" + parameter.name() + "\": new " + parameterProfile + "({")));
        result.add(indent(indent(indent("""
                                            kind: AST.NodeKind.%s,
                                            alias,
                                            name: "%s"
                                            """.formatted(nodeKind, parameter.name())))));
        result.add(indent(indent("}),")));
      }
      result.add(indent("}),"));
    }
    result.add("};");


    Map<String, String> mapInterfaces = new HashMap<>();
    for (final var activityType: activityTypes.entrySet()) {
      final var nameInterface = "ParameterType"+activityType.getKey();
      final var interfaceType = activityTypeToInterface(nameInterface, activityType.getValue());
      mapInterfaces.put(activityType.getKey(), nameInterface);
      result.add(interfaceType);
    }

    result.add("export type ActivityTypeParameterMap = {");
    for (final var activityType: mapInterfaces.entrySet()) {
      result.add(indent("[ActivityType." + activityType.getKey() + "]:"+activityType.getValue()+","));
    }
    result.add("};");


    Map<String, String> mapInterfacesWithUndefined = new HashMap<>();
    for (final var activityType: activityTypes.entrySet()) {
      final var nameInterface = "ParameterTypeWithUndefined"+activityType.getKey();
      final var interfaceType = activityTypeToInterfaceWithUndefined(nameInterface, activityType.getValue());
      mapInterfacesWithUndefined.put(activityType.getKey(), nameInterface);
      result.add(interfaceType);
    }

    result.add("export type ActivityTypeParameterMapWithUndefined = {");
    for (final var activityType: mapInterfacesWithUndefined.entrySet()) {
      result.add(indent("[ActivityType." + activityType.getKey() + "]:"+activityType.getValue()+","));
    }
    result.add("};");


    result.add("""
                   declare global {""");
    result.add(indent("enum ActivityType {"));
    for (String activityType: activityTypes.keySet()) {
      result.add(indent(indent(activityType + " = \"" + activityType + "\",")));
    }
    result.add(indent("}"));
    result.add("""
                   }
                   Object.assign(globalThis, {
                     ActivityType
                   });""");

    result.add("/** End Codegen */");
    return joinLines(result);
  }

  public static String activityTypeToInterface(String typeName, TypescriptCodeGenerationService.ActivityType activityType){
    final var result = new ArrayList<String>();
    result.add("type %s = {".formatted(typeName));
    for (final var parameter: activityType.parameters()) {
      var parameterProfile = valueSchemaToTypescriptAdditional(parameter.schema());
      result.add(indent(parameter.name() + ": " + parameterProfile + ","));
    }
    result.add("}");
    return joinLines(result);
  }

  public static String activityTypeToInterfaceWithUndefined(String typeName, TypescriptCodeGenerationService.ActivityType activityType){
    final var result = new ArrayList<String>();
    result.add("export type %s = {".formatted(typeName));
    for (final var parameter: activityType.parameters()) {
      var parameterProfile = valueSchemaToTypescriptAdditionalWithUndefined(parameter.schema());
      result.add(indent(parameter.name() + "?: " + parameterProfile + ","));
    }
    result.add("}");
    return joinLines(result);
  }


  private static String joinLines(final Iterable<String> result) {
    return String.join("\n", result);
  }

  private static String indent(final String s) {
    return joinLines(s.lines().map(line -> "  " + line).toList());
  }

  static String valueSchemaToTypescriptAdditional(final ValueSchema valueSchema){
    return valueSchema.match(new ValueSchema.Visitor<>() {
      @Override
      public String onReal() {
        return "("+valueSchemaToTypescript(valueSchema) +" | Real)";
      }

      @Override
      public String onInt() {
        final var res = valueSchemaToTypescript(valueSchema);
        //REVIEW: even on int, we are expecting a Real and not a Discrete<number> so we have access to operations,
        // conversion will happen at activity instantiation during scheduling
        return "(%s | Real)".formatted(res);
      }

      @Override
      public String onBoolean() {
        final var res = valueSchemaToTypescript(valueSchema);
        return "(%s | Discrete<%s>)".formatted(res, res);
      }

      @Override
      public String onString() {
        final var res = valueSchemaToTypescript(valueSchema);
        return "(%s | Discrete<%s>)".formatted(res, res);
      }

      @Override
      public String onDuration() {
        final var res = valueSchemaToTypescript(valueSchema);
        return "(AST.Duration | Discrete<%s>)".formatted(res);
      }

      @Override
      public String onPath() { final var res = valueSchemaToTypescript(valueSchema);
        return "(%s | Discrete<%s>)".formatted(res, res);}

      @Override
      public String onSeries(final ValueSchema value) {
        final var vsTs = valueSchemaToTypescriptAdditional(value) + "[]";
        return "(%s | Discrete<%s>)".formatted(vsTs, vsTs);
      }

      @Override
      public String onStruct(final Map<String, ValueSchema> values) {
        final var result = new StringBuilder("{\n");

        for (final var member: values.keySet().stream().sorted().toList()) {
          result
              .append(indent(member))
              .append(": ")
              .append(valueSchemaToTypescriptAdditional(values.get(member)))
              .append(",\n ");
        }

        result.append("}");
        final var res = result.toString();
        return indent("(%s | Discrete<%s>)".formatted(res, res));
      }

      @Override
      public String onVariant(final List<ValueSchema.Variant> variants) {
        final var res = valueSchemaToTypescript(valueSchema);
        return "(%s | Discrete<%s>)".formatted(res, res);
      }

      // TODO: Elevate annotation information
      @Override
      public String onMeta(final Map<String, SerializedValue> metadata, final ValueSchema value) {
        return value.match(this);
      }
    });
  }

  static String valueSchemaToTypescriptAdditionalWithUndefined(final ValueSchema valueSchema){
    return valueSchema.match(new ValueSchema.Visitor<>() {
      @Override
      public String onReal() {
        return "("+valueSchemaToTypescript(valueSchema) +" | Real | undefined)";
      }

      @Override
      public String onInt() {
        final var res = valueSchemaToTypescript(valueSchema);
        //REVIEW: even on int, we are expecting a Real and not a Discrete<number> so we have access to operations,
        // conversion will happen at activity instantiation during scheduling
        return "(%s | Real | undefined)".formatted(res);
      }

      @Override
      public String onBoolean() {
        final var res = valueSchemaToTypescript(valueSchema);
        return "(%s | Discrete<%s> | undefined)".formatted(res, res);
      }

      @Override
      public String onString() {
        final var res = valueSchemaToTypescript(valueSchema);
        return "(%s | Discrete<%s> | undefined)".formatted(res, res);
      }

      @Override
      public String onDuration() {
        final var res = valueSchemaToTypescript(valueSchema);
        return "(%s | Discrete<%s> | undefined)".formatted(res, res);
      }

      @Override
      public String onPath() { final var res = valueSchemaToTypescript(valueSchema);
        return "(%s | Discrete<%s> | undefined)".formatted(res, res);}

      @Override
      public String onSeries(final ValueSchema value) {
        final var vsTs = valueSchemaToTypescriptAdditionalWithUndefined(value) + "[]";
        return "(%s | Discrete<%s> | undefined)".formatted(vsTs, vsTs);
      }

      @Override
      public String onStruct(final Map<String, ValueSchema> values) {
        final var result = new StringBuilder("{\n");

        for (final var member: values.keySet().stream().sorted().toList()) {
          result
              .append(indent(member))
              .append("?: ")
              .append(valueSchemaToTypescriptAdditionalWithUndefined(values.get(member)))
              .append(",\n ");
        }

        result.append("}");
        final var res = result.toString();
        return indent("(%s | Discrete<%s> | undefined)".formatted(res, res));
      }

      @Override
      public String onVariant(final List<ValueSchema.Variant> variants) {
        final var res = valueSchemaToTypescript(valueSchema);
        return "(%s | Discrete<%s> | undefined)".formatted(res, res);
      }

      // TODO Elevate annotation information
      @Override
      public String onMeta(final Map<String, SerializedValue> metadata, final ValueSchema target) {
        return target.match(this);
      }
    });
  }

  private static String valueSchemaToTypescript(final ValueSchema valueSchema) {
    return valueSchema.match(new ValueSchema.Visitor<>() {
      @Override
      public String onReal() {
        return "number";
      }

      @Override
      public String onInt() {
        return "number";
      }

      @Override
      public String onBoolean() {
        return "boolean";
      }

      @Override
      public String onString() {
        return "string";
      }

      @Override
      public String onDuration() {
        return "Temporal.Duration";
      }

      @Override
      public String onPath() {
        return "string";
      }

      @Override
      public String onSeries(final ValueSchema value) {
        return valueSchemaToTypescript(value) + "[]";
      }

      @Override
      public String onStruct(final Map<String, ValueSchema> values) {
        final var result = new StringBuilder("{");

        for (final var member: values.keySet().stream().sorted().toList()) {
          result
              .append(member)
              .append(": ")
              .append(valueSchemaToTypescript(values.get(member)))
              .append(", ");
        }

        result.append("}");
        return result.toString();
      }

      @Override
      public String onVariant(final List<ValueSchema.Variant> variants) {
        final var result = new StringBuilder("(");

        for (final var variant: variants) {
          result
              .append(" | \"")
              .append(variant.label())
              .append("\"");
        }

        result.append(")");
        return result.toString();
      }

      // TODO Elevate annotation information
      @Override
      public String onMeta(final Map<String, SerializedValue> metadata, final ValueSchema target) {
        return target.match(this);
      }
    });
  }

  private static String valueSchemaToTypescriptProfile(final ValueSchema valueSchema) {
    var basicTypescript = valueSchemaToTypescript(valueSchema);
    if (basicTypescript.equals("number")) {
      return "Real";
    } else {
      return "Discrete<" + basicTypescript + ">";
    }
  }

  private static boolean valueSchemaIsNumeric(final ValueSchema valueSchema) {
    return valueSchema.equals(ValueSchema.INT)
        || valueSchema.equals(ValueSchema.REAL)
        || valueSchema.equals(LINEAR_RESOURCE_SCHEMA)
        || valueSchema.asMeta().map($ -> valueSchemaIsNumeric($.target())).orElse(false);
  }
}
