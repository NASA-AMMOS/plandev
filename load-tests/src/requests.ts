import http, { RefinedResponse, ResponseType } from "k6/http";
import * as urls from "../assets/urls";
import { JSONObject, check } from "k6";
import type {LoginResponse, User} from "./types/auth";
import type {
    UploadJarResponse,
    MissionModelInsertInput,
    MissionModelInsertResponse,
} from "./types/model";
import gql from "../assets/gql";
import {CreatePlanInput, CreatePlanResponse, DuplicatePlanResponse, PlanIdList} from "./types/plan";
import {ActivityInsertInput, CreateActivityResponse} from "./types/activity";
import {SimulateResponse} from "./types/simulation";

export const req = {
  hasura(
    query: string,
    variables: JSONObject,
    user: User
  ): RefinedResponse<ResponseType | undefined> {

    const headers = {
      'Authorization': `Bearer ${user.token}`,
      'Content-Type': 'application/json',
      'x-hasura-role': 'aerie_admin',
      'x-hasura-user-id': `${user.username}`
    };

    const body =
      JSON.stringify({
        query,
        variables
      });

    const response = http.post(`${urls.HASURA_URL}/v1/graphql`, body, { headers });

    if (response.error) {
      console.error(response.error);
    }

    return response;
  },

  get_random_plan_id(model_id: number, user: User) {
    const variables = {model_id: model_id}
    const query = `#graphql
      query GetPlansForModel($model_id: Int!) {
        plan(where: {model_id: {_eq: $model_id}}) {
          id
        }
      }
    `;

    const data = req.hasura(query, variables, user).json("data") as PlanIdList;

    const plan_ids = data.plan;
    check(plan_ids, {
      "plans exists": (plan_ids) => plan_ids.length > 0
    });

    const random_plan = plan_ids[Math.floor(Math.random() * plan_ids.length)];
    check(random_plan, {
      "random plan exists": (random_plan) => random_plan !== undefined
    });

    return random_plan.id;
  },

  login(username: string): User {
    const response = http.post(`${urls.GATEWAY_URL}/auth/login`,
    `{
      "username": "${username}",
      "password": "${username}"
    }`,
    {
      headers: {
        'content-type': 'application/json',
      },
    });

    const login_resp = response.json() as LoginResponse;
    return {token: login_resp.token, username: username} as User;
  },

  uploadMissionModel(jar: ArrayBuffer, user: User): number {
    const rd = Math.random() * 10000;

    // upload jar
    const file = {
      type: "application/java-archive",
      file: http.file(jar)
    };

    const res = http.post(`${urls.GATEWAY_URL}/file`, file);

    check(res, {
      'is status 200': (r) => r.status === 200,
    });

    const jar_data = res.json() as UploadJarResponse;
    const jar_id = jar_data.id;

    check(jar_id, {
      "valid id": (id) => Number.isInteger(id)
    });

    // add jar to plandev
    // create mission model
    const modelInput: MissionModelInsertInput = {
      jar_id,
      mission: "aerie-load-test" + rd,
      name: "Banananation (load-test)" + rd,
      version: "0.0.0" + rd,
    };

    const model_resp = req.hasura(gql.CREATE_MISSION_MODEL, {model: modelInput}, user);
    const model_data = model_resp.json("data") as MissionModelInsertResponse;
    check(model_data, {
      "model id is valid": (data) => Number.isInteger(data.insert_mission_model_one.id)
    });
    return model_data.insert_mission_model_one.id;
  },

  createPlan(plan: CreatePlanInput, user: User): number {
    const variables = {plan: plan};
    const data = req.hasura(gql.CREATE_PLAN, variables, user).json("data") as CreatePlanResponse;
    const { insert_plan_one: { id: id } } = data ;
    check(id, {
      "plan id is valid": (id) => Number.isInteger(id)
    });
    return id;
  },

  createActivityDirective(activity: ActivityInsertInput, user: User): number {
    const variables = {activityDirectiveInsertInput: activity};
    const data = req.hasura(gql.CREATE_ACTIVITY_DIRECTIVE, variables, user).json("data") as CreateActivityResponse;
    const { createActivityDirective: { id: id } } = data ;
    check(id, {
    "act dir id is a number": (act_id) => Number.isInteger(act_id)
    });
    return id;
  },

  duplicatePlan(planId: number, newName: string, user: User) {
    const mutation = `
        mutation DuplicatePlan($new_plan_name: String!, $plan_id: Int!) {
            duplicate_plan(args: {new_plan_name: $new_plan_name, plan_id: $plan_id}) {
                new_plan_id
            }
        }`;

    const vars = {
      new_plan_name: newName,
      plan_id: planId
    }
    const data = req.hasura(mutation, vars, user).json("data");
    const { duplicate_plan: { new_plan_id: id } } = data as DuplicatePlanResponse;
    check(id, {
      "new plan id is valid": (id) => Number.isInteger(id)
    });
    return id;
  },

  removePlansForModel(modelId: number, user: User) {
    const query = `
    mutation RemovePlansForModel($model_id: Int!) {
        delete_plan(where: {model_id: {_eq: $model_id}}) {
          affected_rows
        }
     }`; // Doesn't handle scheduling specs

    const vars = { model_id: modelId}
    req.hasura(query, vars, user);
  },

  removeModel(modelId: number, user: User) {
    req.hasura(gql.DELETE_MISSION_MODEL, {id: modelId}, user);
  },

  removePlan(planId: number, user: User) {
    req.hasura(gql.DELETE_PLAN, {id: planId}, user);
  },

  simulate(planId: number, user: User): SimulateResponse {
    const sim_data = req.hasura(gql.SIMULATE, {plan_id: planId}, user).json("data") as SimulateResponse;

    check(sim_data, {
      "sim dataset id is a number": (sim_data) => Number.isInteger(sim_data.simulate.simulationDatasetId)
    });
    return sim_data;
  }
};
