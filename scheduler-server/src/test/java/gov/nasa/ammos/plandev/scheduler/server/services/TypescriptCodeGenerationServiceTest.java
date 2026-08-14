package gov.nasa.jpl.aerie.scheduler.server.services;

import org.junit.jupiter.api.Test;

import static gov.nasa.jpl.aerie.scheduler.server.services.TypescriptCodeGenerationServiceTestFixtures.MISSION_MODEL_TYPES;
import static org.junit.jupiter.api.Assertions.assertEquals;

public final class TypescriptCodeGenerationServiceTest {

  @Test
  void testCodeGen() {
    final var expected = TypescriptCodeGenerationService.generateTypescriptTypesFromMissionModel(MISSION_MODEL_TYPES);
    assertEquals(
        """
           /** Start Codegen */
           import type { ActivityTemplate } from './scheduler-edsl-fluent-api.js';
           import type { Windows } from './constraints-edsl-fluent-api.js';
           import type { ActivityTypeParameterMap } from './mission-model-generated-code.js';
           interface SampleActivity1 extends ActivityTemplate<ActivityType.SampleActivity1> {}
           interface SampleActivity2 extends ActivityTemplate<ActivityType.SampleActivity2> {}
           interface SampleActivity3 extends ActivityTemplate<ActivityType.SampleActivity3> {}
           interface SampleActivityEmpty extends ActivityTemplate<ActivityType.SampleActivityEmpty> {}
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

           const ActivityTemplateConstructors = {
                 /**
                 * Creates a SampleActivity1 instance
                 * @param {Object} args - {duration, fancy, variant}.
                 * @return A SampleActivity1 instance.
                 */
             SampleActivity1: function SampleActivity1Constructor(args:  ActivityTypeParameterMap[ActivityType.SampleActivity1]
             ): SampleActivity1 {
             // @ts-ignore
               return { activityType: ActivityType.SampleActivity1, args: makeArgumentsDiscreteProfiles(args) };
             },
                 /**
                 * Creates a SampleActivity2 instance
                 * @param {Object} args - {quantity}.
                 * @return A SampleActivity2 instance.
                 */
             SampleActivity2: function SampleActivity2Constructor(args:  ActivityTypeParameterMap[ActivityType.SampleActivity2]
             ): SampleActivity2 {
             // @ts-ignore
               return { activityType: ActivityType.SampleActivity2, args: makeArgumentsDiscreteProfiles(args) };
             },
                 /**
                 * Creates a SampleActivity3 instance
                 * @param {Object} args - {variant}.
                 * @return A SampleActivity3 instance.
                 */
             SampleActivity3: function SampleActivity3Constructor(args:  ActivityTypeParameterMap[ActivityType.SampleActivity3]
             ): SampleActivity3 {
             // @ts-ignore
               return { activityType: ActivityType.SampleActivity3, args: makeArgumentsDiscreteProfiles(args) };
             },
             SampleActivityEmpty: function SampleActivityEmptyConstructor(): SampleActivityEmpty {
               return { activityType: ActivityType.SampleActivityEmpty, args: {} };
             },
           };
           const ActivityPresetMap = Object.freeze({
             SampleActivity1: Object.freeze({
             }),
             SampleActivity2: Object.freeze({
               get "my preset"(): { quantity:(number | Real),} {
                 return {
                   "quantity": 5,
                 };
               },
             }),
             SampleActivity3: Object.freeze({
               get "my preset"(): { variant:(("option1" | "option2") | Discrete<("option1" | "option2")>),} {
                 return {
                   "variant": "option1",
                 };
               },
             }),
             SampleActivityEmpty: Object.freeze({
             }),
           });
           export enum Resource {
             "/sample/resource/1" = "/sample/resource/1",
             "/sample/resource/3" = "/sample/resource/3",
             "/sample/resource/2" = "/sample/resource/2",
           };
           declare global {
             var ActivityTemplates: typeof ActivityTemplateConstructors;
             var ActivityPresets: typeof ActivityPresetMap;
             var Resources: typeof Resource;
           }
           // Make ActivityTemplates and ActivityTypes available on the global object
           Object.assign(globalThis, {
             ActivityTemplates: ActivityTemplateConstructors,
             ActivityPresets: ActivityPresetMap,
             ActivityTypes: ActivityType,
             Resources: Resource,
           });
           /** End Codegen */""",
        expected);
  }
}
