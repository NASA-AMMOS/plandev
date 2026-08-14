package gov.nasa.jpl.aerie.scheduler.server.services;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import gov.nasa.jpl.aerie.scheduler.server.models.ActivityType;
import gov.nasa.jpl.aerie.scheduler.server.models.ResourceType;
import org.apache.commons.lang3.tuple.Pair;

import static gov.nasa.jpl.aerie.merlin.driver.json.SerializedValueJsonParser.serializedValueP;

public final class TypescriptCodeGenerationService {
  private TypescriptCodeGenerationService() { }

  public static String generateTypescriptTypesFromMissionModel(final MerlinDatabaseService.MissionModelTypes missionModelTypes) {
    final var activityTypeCodes = new ArrayList<ActivityTypeCode>();
    for (final var activityType : missionModelTypes.activityTypes()) {
      activityTypeCodes.add(getActivityTypeInformation(activityType));
    }
    final var result = new ArrayList<String>();
    result.add("/** Start Codegen */");
    result.add("import type { ActivityTemplate } from './scheduler-edsl-fluent-api.js';");
    result.add("import type { Windows } from './constraints-edsl-fluent-api.js';");
    result.add("import type { ActivityTypeParameterMap } from './mission-model-generated-code.js';");

    for (final var activityTypeCode : activityTypeCodes) {
      result.add("interface %s extends ActivityTemplate<ActivityType.%s> {}".formatted(activityTypeCode.activityTypeName(), activityTypeCode.activityTypeName()));
    }
    result.add(getCastingMethod());
    result.add(generateActivityTemplateConstructors(activityTypeCodes));
    result.add(generateActivityPresetConstructors(activityTypeCodes));
    result.add(generateResourceTypes(missionModelTypes.resourceTypes()));
    result.add("declare global {");
    result.add(indent("var ActivityTemplates: typeof ActivityTemplateConstructors;"));
    result.add(indent("var ActivityPresets: typeof ActivityPresetMap;"));
    result.add(indent("var Resources: typeof Resource;"));
    result.add("}");
    result.add("// Make ActivityTemplates and ActivityTypes available on the global object");
    result.add("Object.assign(globalThis, {");
    result.add(indent("ActivityTemplates: ActivityTemplateConstructors,"));
    result.add(indent("ActivityPresets: ActivityPresetMap,"));
    result.add(indent("ActivityTypes: ActivityType,"));
    result.add(indent("Resources: Resource,"));
    result.add("});");
    result.add("/** End Codegen */");
    return joinLines(result);
  }

  private static String generateResourceTypes(final Collection<ResourceType> resourceTypes) {
    final var result = new ArrayList<String>();
    result.add("export enum Resource {");
    for (final var resourceType : resourceTypes) {
      result.add(indent("\"%s\" = \"%s\",".formatted(resourceType.name(), resourceType.name())));
    }
    result.add("};");
    return joinLines(result);
  }

  private static String getCastingMethod(){
    return """
export function makeAllDiscreteProfile (argument: any) : any{
  if (argument === undefined){
    return undefined
  }
  else if ((argument instanceof Discrete) || (argument instanceof Real)) {
   return argument.__astNode
 } else if ((argument instanceof Temporal.Duration) || (argument.kind === 'IntervalDuration')) {
   return argument;
 } else if(typeof(argument) === "number"){
     if(Number.isInteger(argument)){
       return Discrete.Value(argument).__astNode
     } else{
       return Real.Value(argument).__astNode
     }
   } else if(typeof(argument) === "string" || argument instanceof Temporal.Duration || typeof(argument) === "boolean"){
     return Discrete.Value(argument).__astNode
     }
  else if(Array.isArray(argument)){
   const arr: any[] = [];
   argument.forEach((element) => { arr.push(makeAllDiscreteProfile(element))});
   return Discrete.List(arr).__astNode;
 } else if (typeof argument === "object" ){
   const obj: { [k: string]: any } = {};
   for(var key in (argument)){
     // @ts-ignore
     obj[key] = makeAllDiscreteProfile((<object>argument)[key])
   }
   return Discrete.Map(obj).__astNode;
 } else{
  throw new Error('[makeAllDiscreteProfile] Type not covered: ' + argument);
 }
}

export function makeArgumentsDiscreteProfiles<T>(args : T):T{
// @ts-ignore
return (<T>makeAllDiscreteProfile(args))
}
""";
  }

  private static String generateActivityTemplateConstructors(final Iterable<ActivityTypeCode> activityTypeCodes) {
    final var result = new ArrayList<String>();
    result.add("const ActivityTemplateConstructors = {");
    for (final var activityTypeCode : activityTypeCodes) {
      if(activityTypeCode.parameterTypes().isEmpty()) {
        result.add(indent("%s: function %sConstructor(): %s {".formatted(
            activityTypeCode.activityTypeName(),
            activityTypeCode.activityTypeName(),
            activityTypeCode.activityTypeName())));
        result.add(indent(indent("return { activityType: ActivityType.%s, args: {} };".formatted(activityTypeCode.activityTypeName()))));
        result.add(indent("},"));
      } else {
        final StringBuilder parameters = new StringBuilder();
        activityTypeCode.parameterTypes.stream().forEach((activityParameter -> parameters.append(activityParameter.name+", ")));
        if (parameters.length() > 0) {
          parameters.setLength(parameters.length() - 2); // Remove the last comma and space
        }
        result.add(indent("""
            /**
            * Creates a %s instance
            * @param {Object} args - {%s}.
            * @return A %s instance.
            */
        """.formatted(activityTypeCode.activityTypeName,parameters.toString(),activityTypeCode.activityTypeName)));
        result.add(indent("%s: function %sConstructor(args:  ActivityTypeParameterMap[ActivityType.%s]".formatted(activityTypeCode.activityTypeName,activityTypeCode.activityTypeName,activityTypeCode.activityTypeName)));
        result.add(indent("): %s {".formatted(activityTypeCode.activityTypeName())));
        result.add(indent("// @ts-ignore"));
        result.add(indent(indent("return { activityType: ActivityType.%s, args: makeArgumentsDiscreteProfiles(args) };".formatted(activityTypeCode.activityTypeName()))));
        result.add(indent("},"));
      }
    }
    result.add("};");
    return joinLines(result);
  }

  private static String generateActivityPresetConstructors(final Iterable<ActivityTypeCode> activityTypeCodes) {
    final var result = new ArrayList<String>();
    result.add("const ActivityPresetMap = Object.freeze({");
    for (final var activityTypeCode : activityTypeCodes) {
      result.add(indent("%s: Object.freeze({".formatted(activityTypeCode.activityTypeName)));
      for (final var preset: activityTypeCode.presets.entrySet()) {
        result.add(indent(indent("get \"%s\"(): %s {".formatted(preset.getKey(), TypescriptType.toString(activityTypeCode.getArgumentsType(), false)))));
        result.add(indent(indent(indent("return {"))));
        for (final var argument: preset.getValue().entrySet()) {
          final var deserializedJson = serializedValueP.unparse(argument.getValue());
          result.add(indent(indent(indent(indent("\"%s\": %s,".formatted(argument.getKey(), deserializedJson))))));
        }
        result.add(indent(indent(indent("};"))));
        result.add(indent(indent("},")));
      }
      result.add(indent("}),"));
    }
    result.add("});");
    return joinLines(result);
  }

  private static String joinLines(final Iterable<String> result) {
    return String.join("\n", result);
  }

  private static String indent(final String s) {
    return joinLines(s.lines().map(line -> "  " + line).toList());
  }

  private record ActivityTypeCode(String activityTypeName, List<ActivityParameter> parameterTypes, Map<String, Map<String, SerializedValue>> presets) {
    public TypescriptType getArgumentsType() {
      return new TypescriptType.TSStruct(
          parameterTypes.stream().map($ -> Pair.of($.name(), $.type())).toList()
      );
    }
  }

  private record ActivityParameter(String name, TypescriptType type) {}

  private sealed interface TypescriptType {
    record TSString() implements TypescriptType {}
    record TSDouble() implements TypescriptType {}
    record TSBoolean() implements TypescriptType {}
    record TSInt() implements TypescriptType {}
    record TSDuration() implements TypescriptType {}
    record TSArray(TypescriptType elementType) implements TypescriptType {}
    record TSStruct(List<Pair<String, TypescriptType>> keysAndTypes) implements TypescriptType {}
    record TSEnum(List<String> values) implements TypescriptType {}

    /**
     * Print this type out in one line.
     */
    static String toString(final TypescriptType type, boolean topLevelStructIsProfile) {
      if (type instanceof TSString) {
        return "(string | Discrete<string>)";
      } else if (type instanceof TSDouble) {
        //return "(Double | Real)";
        return "(number | Real)";
      } else if (type instanceof TSBoolean) {
        return "(boolean | Discrete<boolean>)";
      } else if (type instanceof TSInt) {
        return "(number | Real)";
      } else if (type instanceof TSDuration) {
        return "(Temporal.Duration | Discrete<Temporal.Duration>)";
      } else if (type instanceof TSArray t) {
        final var typeStr = toString(t.elementType(), true);
        return "((" +typeStr+ ")[] | Discrete<("+typeStr+")[]>)";
      } else if (type instanceof TSStruct t) {

        var typeStr = new StringBuilder("{ ");
        for (final var keyAndType :  t.keysAndTypes()) {
          typeStr
              .append(keyAndType.getLeft())
              .append(":")
              .append(toString(keyAndType.getRight(), true))
              .append(",");
        }
        typeStr.append("}");
        if (topLevelStructIsProfile) {
          return "(%s | Discrete<%s>)".formatted(typeStr, typeStr);
        } else {
          return typeStr.toString();
        }
      } else if (type instanceof TSEnum t) {
        final var typeStr = "(" + String.join(" | ", t.values().stream().map(x -> "\"" + x + "\"").toList()) + ")";
        return "(%s | Discrete<%s>)".formatted(typeStr, typeStr);
      } else {
        throw new Error("Unhandled variant of TypescriptType: " + type);
      }
    }
  }

  private static ActivityTypeCode getActivityTypeInformation(final ActivityType activityType) {
    return new ActivityTypeCode(activityType.name(), generateActivityParameterTypes(activityType), activityType.presets());
  }

  private static List<ActivityParameter> generateActivityParameterTypes(final ActivityType activityType) {
    return activityType
        .parameters()
        .entrySet()
        .stream()
        .sorted(Map.Entry.comparingByKey())
        .map($ -> new ActivityParameter($.getKey(), valueSchemaToTypescriptType($.getValue())))
        .toList();
  }

  private static TypescriptType valueSchemaToTypescriptType(final ValueSchema valueSchema) {
    return valueSchema.match(new ValueSchema.Visitor<>() {
      @Override
      public TypescriptType onReal() {
        return new TypescriptType.TSDouble();
      }

      @Override
      public TypescriptType onInt() {
        return new TypescriptType.TSInt();
      }

      @Override
      public TypescriptType onBoolean() {
        return new TypescriptType.TSBoolean();
      }

      @Override
      public TypescriptType onString() {
        return new TypescriptType.TSString();
      }

      @Override
      public TypescriptType onDuration() {
        return new TypescriptType.TSDuration();
      }

      @Override
      public TypescriptType onPath() {
        return new TypescriptType.TSString();
      }

      @Override
      public TypescriptType onSeries(final ValueSchema value) {
        return new TypescriptType.TSArray(valueSchemaToTypescriptType(value));
      }

      @Override
      public TypescriptType onStruct(final Map<String, ValueSchema> value) {
        return new TypescriptType.TSStruct(
            value
                .entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey())
                .map($ ->
                         Pair.of(
                             $.getKey(),
                             valueSchemaToTypescriptType($.getValue())))
                .toList());
      }

      @Override
      public TypescriptType onVariant(final List<ValueSchema.Variant> variants) {
        return new TypescriptType.TSEnum(
            variants
                .stream()
                .map(ValueSchema.Variant::label)
                .toList());
      }

      @Override
      public TypescriptType onMeta(final Map<String, SerializedValue> metadata, final ValueSchema target) {
        return target.match(this);
      }
    });
  }

  private static String serializedValueToType(final SerializedValue value) {
    return value.match(new SerializedValue.Visitor<>() {
      @Override
      public String onNull() {
        return "null";
      }

      @Override
      public String onNumeric(final BigDecimal value) {
        return "number";
      }

      @Override
      public String onBoolean(final boolean value) {
        return "boolean";
      }

      @Override
      public String onString(final String value) {
        return "string";
      }

      @Override
      public String onMap(final Map<String, SerializedValue> value) {
        final var result = new ArrayList<String>();
        result.add("{");
        for (final var entry : value.entrySet()) {
          result.add(indent("\"%s\": %s,".formatted(entry.getKey(), serializedValueToType(entry.getValue()))));
        }
        result.add("}");
        return joinLines(result);
      }

      @Override
      public String onList(final List<SerializedValue> value) {
        final var result = new ArrayList<String>();
        result.add("[");
        for (final var entry : value) {
          result.add(indent(serializedValueToType(entry) + ','));
        }
        result.add("]");
        return joinLines(result);
      }
    });
  }
}
