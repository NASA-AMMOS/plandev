package gov.nasa.jpl.aerie.e2e.utils;

public enum GQL {
  ADD_EXTERNAL_DATASET("""
    mutation addExternalDataset($plan_id: Int!, $simulation_dataset_id: Int, $dataset_start: String!, $profile_set: ProfileSet!) {
      addExternalDataset(
        planId: $plan_id
        simulationDatasetId: $simulation_dataset_id
        datasetStart: $dataset_start
        profileSet: $profile_set
      ) {
        datasetId
      }
    }"""),
  ADD_PLAN_COLLABORATOR("""
    mutation addPlanCollaborator($collaborator: plan_collaborators_insert_input!) {
      insert_plan_collaborators_one(object: $collaborator) {
        collaborator
        plan_id
      }
    }"""),
  ASSIGN_TEMPLATE_TO_SIMULATION("""
    mutation AssignTemplateToSimulation($simulation_id: Int!, $simulation_template_id: Int!) {
      update_simulation_by_pk(pk_columns: {id: $simulation_id}, _set: {simulation_template_id: $simulation_template_id}) {
        simulation_template_id
      }
    }"""),
  CANCEL_SCHEDULING("""
    mutation cancelScheduling($analysis_id: Int!) {
      update_scheduling_request_by_pk(pk_columns: {analysis_id: $analysis_id}, _set: {canceled: true}) {
        analysis_id
        specification_id
        specification_revision
        canceled
        reason
        status
      }
    }"""),
  CANCEL_SIMULATION("""
    mutation cancelSimulation($id: Int!) {
      update_simulation_dataset_by_pk(pk_columns: {id: $id}, _set: {canceled: true}) {
        simulationDatasetId: id
        canceled
        reason
        status
      }
    }"""),
  CHECK_CONSTRAINTS("""
    query checkConstraints($planId: Int!, $simulationDatasetId: Int) {
      constraintViolations(planId: $planId, simulationDatasetId: $simulationDatasetId) {
        success
        constraintId
        constraintRevision
        constraintName
        results {
          resourceIds
          gaps {
            end
            start
          }
          violations {
            activityInstanceIds
            windows {
              end
              start
            }
          }
        }
        errors {
          message
          stack
          location {
            column
            line
          }
        }
      }
    }"""),
  CREATE_ACTIVITY_DIRECTIVE("""
    mutation CreateActivityDirective($activityDirectiveInsertInput: activity_directive_insert_input!) {
      createActivityDirective: insert_activity_directive_one(object: $activityDirectiveInsertInput) {
        id
      }
    }"""),
  CREATE_MISSION_MODEL("""
    mutation CreateMissionModel($model: mission_model_insert_input!) {
      insert_mission_model_one(object: $model) {
        id
      }
    }"""),
  CREATE_PLAN("""
    mutation CreatePlan($plan: plan_insert_input!) {
      insert_plan_one(object: $plan) {
        id
        revision
      }
    }"""),
  CREATE_SCHEDULING_SPEC_GOAL("""
    mutation CreateSchedulingSpecGoal($spec_goal: scheduling_specification_goals_insert_input!) {
      insert_scheduling_specification_goals_one(object: $spec_goal) {
        goal_id
        goal_invocation_id
        priority
        specification_id
      }
    }"""),
  CREATE_USER("""
    mutation createUser($user: users_insert_input!, $allowed_roles: [users_allowed_roles_insert_input!]!) {
      insert_users_one(object: $user) {
        default_role
        username
      }
      insert_users_allowed_roles(objects: $allowed_roles) {
        returning {
          allowed_role
          username
        }
      }
    }"""),
  DELETE_ACTIVITY_DIRECTIVE("""
    mutation DeleteActivityDirective($id: Int!, $plan_id: Int!) {
      delete_activity_directive_by_pk(id: $id, plan_id: $plan_id) {
        id
      }
    }"""),
  DELETE_CONSTRAINT("""
    mutation DeleteConstraint($id: Int!) {
      delete_constraint_specification(where: {constraint_id: {_eq: $id}}){
        affected_rows
      }
      delete_constraint_metadata_by_pk(id: $id) {
        id
      }
    }"""),
  DELETE_EXTERNAL_DATASET("""
    mutation deleteExtProfile($plan_id: Int!, $dataset_id: Int!) {
      delete_plan_dataset_by_pk(plan_id:$plan_id, dataset_id:$dataset_id) {
        dataset_id
      }
    }"""),
  DELETE_MISSION_MODEL("""
    mutation DeleteModel($id: Int!) {
      delete_mission_model_by_pk(id: $id) {
        id
      }
    }"""),
  DELETE_PLAN("""
    mutation DeletePlan($id: Int!) {
      deletePlan: delete_plan_by_pk(id: $id) {
        id
      }
      deleteSchedulingSpec: delete_scheduling_specification(where: { plan_id: { _eq: $id } }) {
        returning {
          id
        }
      }
      deleteSimulation: delete_simulation(where: { plan_id: { _eq: $id } }) {
        returning {
          id
        }
      }
      deleteConstraintSpec: delete_constraint_specification(where: {plan_id: {_eq: $id}}){
        returning {
          constraint_id
          constraint_revision
        }
      }
    }"""),
  DELETE_SCHEDULING_GOAL("""
    mutation DeleteSchedulingGoal($goalId: Int!) {
      delete_scheduling_specification_goals(where: {goal_id: {_eq: $goalId}}){
        affected_rows
      }
      delete_scheduling_goal_metadata_by_pk(id: $goalId) {
        name
      }
    }"""),
  DELETE_SIMULATION_PRESET("""
    mutation DeleteSimPreset($templateId: Int!) {
      delete_simulation_template_by_pk(id: $templateId) {
        id
      }
    }"""),
  DELETE_USER("""
    mutation deleteUser($username: String!) {
      delete_users_by_pk(username: $username) {
        username
        default_role
      }
    }"""),
  EXTEND_EXTERNAL_DATASET("""
    mutation extendExternalDataset($dataset_id: Int!, $profile_set: ProfileSet!) {
      extendExternalDataset(
        datasetId: $dataset_id
        profileSet: $profile_set
      ) {
        datasetId
      }
    }"""),
  GET_ACTIVITY_TYPES("""
    query GetActivityTypes($missionModelId: Int!) {
      activity_type(where: {model_id: {_eq: $missionModelId}}, order_by: {name: asc}) {
        name
        parameters
        computed_attributes_value_schema
      }
    }"""),
  GET_CONSTRAINT_RUNS("""
    query getConstraintRuns($simulationDatasetId: Int!) {
      constraint_run(where: {simulation_dataset_id: {_eq: $simulationDatasetId}}) {
        constraint_id
        constraint_revision
        simulation_dataset_id
        results
        constraint_definition {
          definition
        }
      }
    }"""),
  GET_EFFECTIVE_ACTIVITY_ARGUMENTS_BULK("""
    query GetEffectiveActivityArgumentsBulk($modelId: ID!, $activities: [EffectiveArgumentsInput!]!) {
      getActivityEffectiveArgumentsBulk(
        missionModelId: $modelId,
        activities: $activities
      ) {
        typeName
        arguments
        errors
        success
      }
    }"""),
  GET_ACTIVITY_VALIDATIONS("""
    query GetActivityValidations($planId: Int!) {
      activity_directive_validations(where: {plan_id: {_eq: $planId}}) {
        status
        validations
        plan_id
        last_modified_arguments_at
        directive_id
      }
    }"""),
  GET_EFFECTIVE_MODEL_ARGUMENTS("""
    query GetEffectiveModelArguments($modelId: ID!, $modelArgs: ModelArguments!) {
      getModelEffectiveArguments(missionModelId: $modelId, modelArguments: $modelArgs) {
        arguments
        errors
        success
      }
    }"""),
  GET_EXTERNAL_DATASET("""
    query getExternalDataset($plan_id: Int!, $dataset_id: Int!) {
      externalDataset: plan_dataset_by_pk(plan_id:$plan_id, dataset_id:$dataset_id) {
        plan_id
        dataset_id
        simulation_dataset_id
        offset_from_plan_start
        dataset {
          profiles(distinct_on:[], order_by: { name: asc }) {
            name
            profile_segments(distinct_on: []) {
              start_offset
              is_gap
              dynamics
            }
          }
        }
      }
    }"""),
  GET_MODEL_EVENT_LOGS("""
    query getModelLogs($modelId: Int!) {
      mission_model: mission_model_by_pk(id:$modelId) {
        id
        name
        version
        refresh_activity_type_logs(order_by: {created_at: desc}) {
          triggering_user
          pending
          delivered
          success
          tries
          created_at
          status
          error
          error_message
          error_type
        }
        refresh_model_parameter_logs(order_by: {created_at: desc}) {
          triggering_user
          pending
          delivered
          success
          tries
          created_at
          status
          error
          error_message
          error_type
        }
        refresh_resource_type_logs(order_by: {created_at: desc}) {
          triggering_user
          pending
          delivered
          success
          tries
          created_at
          status
          error
          error_message
          error_type
        }
      }
    }"""),
  GET_PLAN("""
    query GetPlan($id: Int!) {
      plan: plan_by_pk(id: $id) {
        activity_directives {
          arguments
          id
          plan_id
          startOffset: start_offset
          type
        }
        constraint_specification {
          constraint_id
          constraint_revision
          constraint_metadata{
            name
            description
          }
          constraint_definition {
            definition
          }
        }
        duration
        id
        model: mission_model {
          activityTypes: activity_types {
            name
            parameters
          }
          id
          parameters {
            parameters
          }
        }
        name
        revision
        scheduling_specification {
          id
        }
        simulations {
          arguments
          id
          simulation_start_time
          simulation_end_time
          template: simulation_template {
            arguments
            description
            id
          }
        }
        startTime: start_time
      }
    }"""),
  GET_PLAN_REVISION("""
    query GetPlanRevision($id: Int!) {
      plan: plan_by_pk(id: $id) {
        revision
      }
    }"""),
  GET_PROFILES("""
    query GetProfiles($datasetId: Int!){
      profile(where: {dataset_id: {_eq: $datasetId}}) {
        name
        profile_segments {
          dynamics
          is_gap
          start_offset
        }
    }
    }"""),
  GET_RESOURCE_TYPES("""
    query GetResourceTypes($missionModelId: Int!) {
      resource_type(where: {model_id: {_eq: $missionModelId}}, order_by: {name: asc}) {
        name
        schema
      }
    }"""),
  GET_ROLE_ACTION_PERMISSIONS("""
    query getRolePermissions($role: user_roles_enum!){
      permissions: user_role_permission_by_pk(role: $role) {
        action_permissions
      }
    }"""),
  GET_SCHEDULING_DSL_TYPESCRIPT("""
    query GetSchedulingDslTypeScript($missionModelId: Int!, $planId: Int) {
      schedulingDslTypescript(missionModelId: $missionModelId, planId: $planId) {
        reason
        status
        typescriptFiles {
          filePath
          content
        }
      }
    }"""),
  GET_SCHEDULING_REQUEST("""
    query GetSchedulingRequest($analysisId: Int!) {
      scheduling_request_by_pk(analysis_id: $analysisId) {
        specification_id
        specification_revision
        analysis_id
        canceled
        reason
        status
      }
    }"""),
  GET_SCHEDULING_SPECIFICATION_ID("""
    query GetSchedulingSpec($planId: Int!) {
      scheduling_spec: scheduling_specification(where: {plan_id: {_eq: $planId}}) {
        id
      }
    }"""),
  GET_SCHEDULING_SPECIFICATION_GOALS("""
    query GetSchedulingSpecGoals($specId: Int!) {
      goals: scheduling_specification_goals(where: {specification_id: {_eq: $specId}}) {
        goal_id
      }
    }"""),
  GET_SIMULATION_CONFIGURATION("""
    query GetSimConfig($planId: Int!) {
      sim_config: simulation(where: {plan_id: {_eq:$planId}}) {
        id
        revision
        plan_id
        simulation_template_id
        arguments
        simulation_start_time
        simulation_end_time
      }
    }"""),
  GET_SIMULATION_DATASET("""
    query GetSimulationDataset($id: Int!) {
      simulationDataset: simulation_dataset_by_pk(id: $id) {
        status
        reason
        canceled
        dataset_id
        simulation_start_time
        simulation_end_time
        simulated_activities {
          activity_directive { id }
          duration
          start_time
          start_offset
          parent_id
          type: activity_type_name
          id
        }
      }
    }"""),
  GET_SIMULATION_DATASET_BY_DATASET_ID("""
    query GetSimulationDataset($id: Int!) {
      simulation_dataset(where: {dataset_id: {_eq: $id}}) {
        status
        reason
        canceled
        dataset_id
        simulation_start_time
        simulation_end_time
        simulated_activities {
          activity_directive { id }
          duration
          start_time
          start_offset
          parent_id
          type: activity_type_name
          id
        }
      }
    }"""),
  GET_SIMULATION_ID("""
    query getSimulationId($plan_id: Int!) {
      simulation: simulation(where: {plan_id: {_eq: $plan_id}}) {
        id
      }
    }"""),
  GET_TOPIC_EVENTS("""
    query GetTopicsEvents($datasetId: Int!) {
      topic(where: {dataset_id: {_eq: $datasetId}}) {
        name
        value_schema
        events {
          causal_time
          real_time
          topic_index
          transaction_index
          value
        }
      }
    }"""),
  INSERT_PLAN_SPEC_CONSTRAINT("""
    mutation insertConstraintAssignToPlanSpec($constraint: constraint_specification_insert_input!) {
      constraint: insert_constraint_specification_one(object: $constraint){
        constraint_id
      }
    }"""),
  INSERT_PROFILE("""
    mutation insertProfile($datasetId: Int!, $duration:interval, $name:String, $type:jsonb){
      insert_profile_one(object: {dataset_id: $datasetId, duration: $duration, name: $name, type: $type}) {
        id
      }
    }"""),
  INSERT_PROFILE_SEGMENTS("""
    mutation insertProfileSegment($segments: [profile_segment_insert_input!]!){
      insert_profile_segment(objects: $segments){
        affected_rows
      }
    }"""),
  INSERT_SIMULATION_DATASET("""
    mutation InsertSimulationDataset($simulationDataset:simulation_dataset_insert_input!){
      simulation_dataset: insert_simulation_dataset_one(object: $simulationDataset) {
        dataset_id
      }
    }"""),
  INSERT_SIMULATION_TEMPLATE("""
    mutation CreateSimulationTemplate($simulationTemplate: simulation_template_insert_input!) {
      template: insert_simulation_template_one(object: $simulationTemplate) {
        id
      }
    }"""),
  INSERT_SPAN("""
    mutation InsertSpan($span: span_insert_input!){
      span: insert_span_one(object: $span) {
        span_id
      }
    }"""),
  SCHEDULE("""
    query Schedule($specificationId: Int!) {
      schedule(specificationId: $specificationId){
        reason
        status
        analysisId
        datasetId
      }
    }"""),
  SIMULATE("""
    query Simulate($plan_id: Int!) {
      simulate(planId: $plan_id){
        status
        reason
        simulationDatasetId
      }
    }"""),
  SIMULATE_FORCE("""
    query SimulateForce($plan_id: Int!, $force: Boolean) {
      simulate(planId: $plan_id, force: $force){
        status
        reason
        simulationDatasetId
      }
    }"""),
  UPDATE_ACTIVITY_DIRECTIVE_ARGUMENTS("""
    mutation updateActivityDirectiveArguments($id: Int!, $plan_id: Int!, $arguments: jsonb!) {
      updateActivityDirectiveArguments: update_activity_directive_by_pk(
        pk_columns: {id: $id, plan_id: $plan_id},
        _set: {arguments: $arguments}
      ) {
        id
      }
  }"""),
  UPDATE_CONSTRAINT("""
    mutation updateConstraint($constraintId: Int!, $constraintDefinition: String!) {
      constraint: insert_constraint_definition_one(object: {constraint_id: $constraintId, definition: $constraintDefinition}) {
        definition
        revision
      }
    }"""),
  UPDATE_CONSTRAINT_SPEC_VERSION("""
      mutation updateConstraintSpecVersion($plan_id: Int!, $constraint_id: Int!, $constraint_revision: Int!) {
        update_constraint_specification_by_pk(
          pk_columns: {constraint_id: $constraint_id, plan_id: $plan_id},
          _set: {constraint_revision: $constraint_revision}
        ) {
          plan_id
          constraint_id
          constraint_revision
          enabled
        }
      }"""),
  UPDATE_CONSTRAINT_SPEC_ENABLED("""
      mutation updateConstraintSpecVersion($plan_id: Int!, $constraint_id: Int!, $enabled: Boolean!) {
        update_constraint_specification_by_pk(
          pk_columns: {constraint_id: $constraint_id, plan_id: $plan_id},
          _set: {enabled: $enabled}
        ) {
          plan_id
          constraint_id
          constraint_revision
          enabled
        }
      }"""),
  UPDATE_GOAL_DEFINITION("""
    mutation updateGoalDefinition($goal_id: Int!, $definition: String!) {
      definition: insert_scheduling_goal_definition_one(object: {goal_id: $goal_id, definition: $definition}) {
        revision
      }
    }"""),
  UPDATE_ROLE_ACTION_PERMISSIONS("""
    mutation updateRolePermissions($role: user_roles_enum!, $action_permissions: jsonb!) {
      permissions: update_user_role_permission_by_pk(
        pk_columns: {role: $role},
        _set: {action_permissions: $action_permissions})
      {
        action_permissions
      }
    }"""),
  UPDATE_SCHEDULING_SPEC_GOALS_ARGUMENTS("""
		mutation updateSchedulingSpecGoalArguments($goal_invocation_id: Int!, $arguments: jsonb!) {
			update_scheduling_specification_goals_by_pk(
			  pk_columns: {goal_invocation_id: $goal_invocation_id},
			  _set: {arguments: $arguments})
			{
				goal_revision
				arguments
			}
		}"""),
  UPDATE_SCHEDULING_SPEC_GOALS_ENABLED("""
		mutation updateSchedulingSpecGoalVersion($goal_invocation_id: Int!, $enabled: Boolean!) {
			update_scheduling_specification_goals_by_pk(
			  pk_columns: {goal_invocation_id: $goal_invocation_id},
			  _set: {enabled: $enabled})
			{
				goal_revision
				enabled
			}
		}"""),
  UPDATE_SCHEDULING_SPEC_GOALS_VERSION("""
		mutation updateSchedulingSpecGoalVersion($goal_invocation_id: Int!, $goal_revision: Int!) {
			update_scheduling_specification_goals_by_pk(
				pk_columns: {goal_invocation_id: $goal_invocation_id},
				_set: {goal_revision: $goal_revision})
			{
				goal_revision
				enabled
			}
		}"""),
  UPDATE_SCHEDULING_SPECIFICATION_PLAN_REVISION("""
    mutation updateSchedulingSpec($planId: Int!, $planRev: Int!) {
      update_scheduling_specification(
        where: {plan_id: {_eq: $planId}},
        _set: {plan_revision: $planRev})
      {
        affected_rows
      }
    }"""),
  UPDATE_SIMULATION_ARGUMENTS("""
    mutation updateSimulationArguments($plan_id: Int!, $arguments: jsonb!) {
      update_simulation(where: {plan_id: {_eq: $plan_id}},
      _set: { arguments: $arguments }) {
        affected_rows
       }
    }"""),
  UPDATE_SIMULATION_BOUNDS("""
    mutation updateSimulationBounds($plan_id: Int!, $simulation_start_time: timestamptz!, $simulation_end_time: timestamptz!) {
      update_simulation(where: {plan_id: {_eq: $plan_id}},
      _set: {
        simulation_start_time: $simulation_start_time,
        simulation_end_time: $simulation_end_time}) {
        affected_rows
       }
    }""");
  public final String query;
  GQL(String query) {
    this.query = query;
  }
}
