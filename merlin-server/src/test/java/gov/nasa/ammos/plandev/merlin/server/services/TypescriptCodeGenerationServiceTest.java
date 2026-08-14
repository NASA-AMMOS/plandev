package gov.nasa.jpl.aerie.merlin.server.services;

import gov.nasa.jpl.aerie.merlin.driver.MissionModelLoader;
import gov.nasa.jpl.aerie.merlin.server.exceptions.NoSuchPlanException;
import gov.nasa.jpl.aerie.merlin.server.mocks.StubMissionModelService;
import gov.nasa.jpl.aerie.merlin.server.mocks.StubPlanService;
import gov.nasa.jpl.aerie.merlin.server.models.PlanId;
import gov.nasa.jpl.aerie.types.MissionModelId;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TypescriptCodeGenerationServiceTest {

  @Test
  void testCodeGen() throws MissionModelService.NoSuchMissionModelException, NoSuchPlanException, MissionModelLoader.MissionModelLoadException {
    final var codeGenService = new TypescriptCodeGenerationServiceAdapter(new StubMissionModelService(), new StubPlanService());
    final var expected = codeGenService.generateTypescriptTypes(new MissionModelId(1L),
                                                                Optional.of(new PlanId(1L)), Optional.empty());
    assertEquals(expected,
        """
            /** Start Codegen */
            import * as AST from './constraints-ast.js';
            import { Discrete, Real, Windows, ActivityInstance } from './constraints-edsl-fluent-api.js';
            export enum ActivityType {
              activity2 = "activity2",
              activity = "activity",
            }
            export type Resource = {
              "mode": ( | "Option1" | "Option2"),
              "state of charge": {initial: number, rate: number, },
              "an integer": number,
              "external resource": boolean,
            };
            export type ResourceName = "mode" | "state of charge" | "an integer" | "external resource" | never;
            export type RealResourceName = "state of charge" | "an integer" | never;
            export const ActivityTypeParameterInstantiationMap = {
              [ActivityType.activity2]: (alias: string) => ({
                "Param": new Discrete<( | "hello" | "there")>({
                  kind: AST.NodeKind.DiscreteProfileParameter,
                  alias,
                  name: "Param"
                }),
              }),
              [ActivityType.activity]: (alias: string) => ({
                "Param": new Discrete<string>({
                  kind: AST.NodeKind.DiscreteProfileParameter,
                  alias,
                  name: "Param"
                }),
                "AnotherParam": new Real({
                  kind: AST.NodeKind.RealProfileParameter,
                  alias,
                  name: "AnotherParam"
                }),
                "Duration": new Discrete<Temporal.Duration>({
                  kind: AST.NodeKind.DiscreteProfileParameter,
                  alias,
                  name: "Duration"
                }),
              }),
            };
            type ParameterTypeactivity2 = {
              Param: (( | "hello" | "there") | Discrete<( | "hello" | "there")>),
            }
            type ParameterTypeactivity = {
              Param: (string | Discrete<string>),
              AnotherParam: (number | Real),
              Duration: (AST.Duration | Discrete<Temporal.Duration>),
            }
            export type ActivityTypeParameterMap = {
              [ActivityType.activity2]:ParameterTypeactivity2,
              [ActivityType.activity]:ParameterTypeactivity,
            };
            export type ParameterTypeWithUndefinedactivity2 = {
              Param?: (( | "hello" | "there") | Discrete<( | "hello" | "there")> | undefined),
            }
            export type ParameterTypeWithUndefinedactivity = {
              Param?: (string | Discrete<string> | undefined),
              AnotherParam?: (number | Real | undefined),
              Duration?: (Temporal.Duration | Discrete<Temporal.Duration> | undefined),
            }
            export type ActivityTypeParameterMapWithUndefined = {
              [ActivityType.activity2]:ParameterTypeWithUndefinedactivity2,
              [ActivityType.activity]:ParameterTypeWithUndefinedactivity,
            };
            declare global {
              enum ActivityType {
                activity2 = "activity2",
                activity = "activity",
              }
            }
            Object.assign(globalThis, {
              ActivityType
            });
            /** End Codegen */"""
    );
  }
}
