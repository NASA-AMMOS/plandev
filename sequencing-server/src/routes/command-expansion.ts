import type { UserCodeError } from '@nasa-jpl/aerie-ts-user-code-runner';
import pgFormat from 'pg-format';
import { Context, db, piscina, promiseThrottler, typeCheckingCache } from './../app.js';
import { Result } from '@nasa-jpl/aerie-ts-user-code-runner/build/utils/monads.js';
import express from 'express';
import { serializeWithTemporal } from './../utils/temporalSerializers.js';
import { generateTypescriptForGraphQLActivitySchema } from './../lib/codegen/ActivityTypescriptCodegen.js';
import { isRejected, isResolved } from './../utils/typeguards.js';
import type { executeExpansionFromBuildArtifacts, typecheckExpansion } from './../worker.js';
import getLogger from './../utils/logger.js';
import { InheritedError } from '../utils/InheritedError.js';
import { unwrapPromiseSettledResults } from '../lib/batchLoaders/index.js';
import { seqJsonBuilder } from '../seqJsonBuilder.js';
import { ActivateStep, CommandStem, LoadStep } from './../lib/codegen/CommandEDSLPreface.js';
import { getUsername } from '../utils/hasura.js';
import * as crypto from 'crypto';

const logger = getLogger('app');

export const commandExpansionRouter = express.Router();

commandExpansionRouter.post('/put-expansion', async (req, res, next) => {
  const context: Context = res.locals['context'];

  const activityTypeName = req.body.input.activityTypeName as string;
  const expansionLogic = req.body.input.expansionLogic as string;
  const parcelId = req.body.input.parcelId as number | null;
  const authoringMissionModelId = req.body.input.authoringMissionModelId as number | null;

  const { rows } = await db.query(
    `
    insert into sequencing.expansion_rule (activity_type, expansion_logic, parcel_id,
                                authoring_mission_model_id)
    values ($1, $2, $3, $4)
    returning id;
  `,
    [activityTypeName, expansionLogic, parcelId, authoringMissionModelId],
  );

  if (rows.length < 1) {
    throw new Error(`POST /put-expansion: No expansion was updated in the database`);
  }

  const id = rows[0].id;
  logger.info(`POST /put-expansion: Updated expansion in the database: id=${id}`);

  if (authoringMissionModelId == null || parcelId == null) {
    res.status(200).json({ id });
    return next();
  }

  // WHY NOT DO THIS FIRST?
  const parcel = await context.parcelTypescriptDataLoader.load({ parcelId });
  const commandTypes = await context.commandTypescriptDataLoader.load({ dictionaryId: parcel.command_dictionary.id });
  const activitySchema = await context.activitySchemaDataLoader.load({
    missionModelId: authoringMissionModelId,
    activityTypeName,
  });
  const activityTypescript = generateTypescriptForGraphQLActivitySchema(activitySchema);
  const result = await promiseThrottler.run(() => {
    return (
      piscina.run(
        {
          commandTypes: commandTypes,
          activityTypes: activityTypescript,
          activityTypeName: activityTypeName,
        },
        { name: 'typecheckExpansion' },
      ) as ReturnType<typeof typecheckExpansion>
    ).then(Result.fromJSON);
  });

  res.status(200).json({ id, errors: result.isErr() ? result.unwrapErr() : [] });
  return next();
});

commandExpansionRouter.post('/put-template', async (req, res, next) => {
  const name = req.body.input.name as string;
  const parcelId = req.body.input.parcelId as number | null;
  const modelId = req.body.input.modelId as number | null;
  const activityTypeName = req.body.input.activityTypeName as string;
  let language = req.body.input.language as string;
  const username = getUsername(req.body.session_variables, req.headers.authorization);

  // if this makes use of helpers, which is possible, there's no easy way to verify this is valid mustache without
  //    getting accurate sample input.
  //    i.e. if I have a template "CMD {{ data }} " and pass it input={}, I'll get "CMD ", without error. BUT
  //         if I have a template "CMD WHEN={{ clean-date date }}" and pass it input={}, I'll get a failure.
  //    Since this cannot be anticipated ahead of time, we don't pre-compile/verify here.
  const templateDefinition = req.body.input.templateDefinition as string;

  if (modelId == null || parcelId == null) {
    res.status(500).json({ errors: ["Must include parcelId and authoringMissionModelId."] });
    return next();
  }
  if (["stol", "seqn", "text"].indexOf(language.toLowerCase()) === -1) {
    res.status(500).json({ errors: [`Invalid language ${language}; must be "STOL", "SeqN", or "Text".`] });
    return next();
  }

  if (language.toLowerCase() === "stol") language = "STOL";
  if (language.toLowerCase() === "seqn") language = "SeqN";
  if (language.toLowerCase() === "text") language = "Text";

  const { rows } = await db.query(
    `
    insert into sequencing.sequence_template (name, model_id, parcel_id, template_definition, activity_type, language, owner)
    values ($1, $2, $3, $4, $5, $6, $7)
    returning id;
  `,
    [name, modelId, parcelId, templateDefinition, activityTypeName, language, username],
  );

  if (rows.length < 1) {
    throw new Error(`POST /put-template: No template was updated in the database`);
  }

  const id = rows[0].id;
  logger.info(`POST /put-template: Updated template in the database: id=${id}`);

  res.status(200).json({ id });
  return next();
});

commandExpansionRouter.post('/put-expansion-set', async (req, res, next) => {
  const context: Context = res.locals['context'];
  const username = getUsername(req.body.session_variables, req.headers.authorization);

  const parcelId = req.body.input.parcelId as number;
  const missionModelId = req.body.input.missionModelId as number;
  const expansionIds = req.body.input.expansionIds as number[];
  const description = req.body.input.description as string | null;
  const name = req.body.input.name as string;

  const [expansions, parcel] = await Promise.all([
    context.expansionDataLoader.loadMany(expansionIds.map(id => ({ expansionId: id }))),
    context.parcelTypescriptDataLoader.load({ parcelId }),
  ]);

  if (!parcel) {
    throw new InheritedError(`No parcel found with id: ${parcelId}`, {
      name: 'ParcelNotFoundError',
      stack: null,
      // @ts-ignore  Message is not spread when it comes from an Error object because it's a getter
      message: `No parcel found with id: ${parcelId}`,
    });
  }

  if (!parcel.command_dictionary) {
    throw new InheritedError(`No command dictionary within id: ${parcelId}`, {
      name: 'CommandDictionaryNotFoundError',
      stack: null,
      // @ts-ignore  Message is not spread when it comes from an Error object because it's a getter
      message: `No command dictionary within id: ${parcelId}`,
    });
  }

  const [commandTypes] = await Promise.all([
    context.commandTypescriptDataLoader.load({ dictionaryId: parcel.command_dictionary.id }),
  ]);

  const typecheckErrorPromises = await Promise.allSettled(
    expansions.map(async (expansion, index) => {
      if (expansion instanceof Error) {
        throw new InheritedError(`Expansion with id: ${expansionIds[index]} could not be loaded`, expansion);
      }

      const hash = crypto
        .createHash('sha256')
        .update(
          JSON.stringify({
            parcelID: parcel.id,
            commandDictionaryId: parcel.command_dictionary.id,
            parameterDictionaryId: parcel.parameter_dictionaries.map(param => param.parameter_dictionary.id),
            ...(parcel.channel_dictionary ? { channelDictionaryId: parcel.channel_dictionary.id } : {}),
            missionModelId,
            expansionId: expansion.id,
            expansionLogic: expansion.expansionLogic,
            activityType: expansion.activityType,
          }),
        )
        .digest('hex');

      if (typeCheckingCache.has(hash)) {
        console.log(`Using cached typechecked data for ${expansion.activityType}`);
        return typeCheckingCache.get(hash);
      }

      const activitySchema = await context.activitySchemaDataLoader.load({
        missionModelId,
        activityTypeName: expansion.activityType,
      });
      const activityTypescript = generateTypescriptForGraphQLActivitySchema(activitySchema);
      const typeCheckResult = promiseThrottler.run(() => {
        return (
          piscina.run(
            {
              expansionLogic: expansion.expansionLogic,
              commandTypes: commandTypes,
              activityTypes: activityTypescript,
              activityTypeName: expansion.activityType,
            },
            { name: 'typecheckExpansion' },
          ) as ReturnType<typeof typecheckExpansion>
        ).then(Result.fromJSON);
      });

      typeCheckingCache.set(hash, typeCheckResult);
      return typeCheckResult;
    }),
  );

  const errors = unwrapPromiseSettledResults(typecheckErrorPromises).reduce((accum, item) => {
    if (item && (item instanceof Error || item.isErr)) {
      // Check for item's existence before accessing properties
      if (item instanceof Error) {
        accum.push(item);
      } else if (item.isErr()) {
        try {
          accum.push(...item.unwrapErr()); // Handle potential errors within unwrapErr
        } catch (error) {
          accum.push(new Error('Failed to unwrap error: ' + error)); // Log unwrapErr errors
        }
      }
    } else {
      accum.push(new Error('Unexpected result in resolved promises')); // Handle unexpected non-error values
    }

    return accum;
  }, [] as (Error | ReturnType<UserCodeError['toJSON']>)[]);

  if (errors.length > 0) {
    throw new InheritedError(
      `Expansion set could not be type checked`,
      errors.map(e => ({
        name: 'TypeCheckError',
        stack: e.stack ?? null,
        // @ts-ignore  Message is not spread when it comes from an Error object because it's a getter
        message: e.message,
        ...e,
      })),
    );
  }

  const { rows } = await db.query(
    `
        with expansion_set_id as (
          insert into sequencing.expansion_set (parcel_id, mission_model_id, description, owner, name)
            values ($1, $2, $3, $4, $5)
            returning id),
             rules as (select id, activity_type from sequencing.expansion_rule where id = any ($6::int[]) order by id)
        insert
        into sequencing.expansion_set_to_rule (set_id, rule_id, activity_type)
        select a.id, b.id, b.activity_type
        from (select id from expansion_set_id) a,
             (select id, activity_type from rules) b
        returning (select id from expansion_set_id);
      `,
    [parcelId, missionModelId, description ?? '', username, name ?? '', expansionIds],
  );

  if (rows.length < 1) {
    throw new Error(`POST /command-expansion/put-expansion-set: No expansion set was inserted in the database`);
  }
  const id = rows[0].id;
  logger.info(`POST /command-expansion/put-expansion-set: Updated expansion set in the database: id=${id}`);
  res.status(200).json({ id });
  return next();
});

commandExpansionRouter.post('/expand-all-activity-instances', async (req, res, next) => {
  const context: Context = res.locals['context'];

  // Query for expansion set data
  const expansionSetId = req.body.input.expansionSetId as number;
  const simulationDatasetId = req.body.input.simulationDatasetId as number;
  const [expansionSet, simulatedActivities] = await Promise.all([
    context.expansionSetDataLoader.load({ expansionSetId }),
    context.simulatedActivitiesDataLoader.load({ simulationDatasetId }),
  ]);

  const missionModelId = expansionSet.missionModel.id;
  const commandTypes = expansionSet.parcel.command_dictionary.commandTypesTypeScript;

  const settledExpansionResults = await Promise.allSettled(
    simulatedActivities.map(async simulatedActivity => {
      // The simulatedActivity's duration and endTime will be null if the effect model reaches across the plan end boundaries.
      if (!simulatedActivity.duration && !simulatedActivity.endTime) {
        return {
          activityInstance: simulatedActivity,
          commands: null,
          errors: [{ message: 'Duration is null' }],
        };
      }

      const activitySchema = await context.activitySchemaDataLoader.load({
        missionModelId: expansionSet.missionModel.id,
        activityTypeName: simulatedActivity.activityTypeName,
      });
      const expansion = expansionSet.expansionRules.find(
        expansionRule => expansionRule.activityType === simulatedActivity.activityTypeName,
      );

      if (expansion === undefined) {
        return {
          activityInstance: simulatedActivity,
          commands: null,
          errors: null,
        };
      }
      const activityTypes = generateTypescriptForGraphQLActivitySchema(activitySchema);

      const hash = crypto
        .createHash('sha256')
        .update(
          JSON.stringify({
            parcelID: expansionSet.parcel.id,
            commandDictionaryId: expansionSet.parcel.command_dictionary.id,
            parameterDictionaryId: expansionSet.parcel.parameter_dictionaries.map(
              param => param.parameter_dictionary.id,
            ),
            ...(expansionSet.parcel.channel_dictionary
              ? { channelDictionaryId: expansionSet.parcel.channel_dictionary.id }
              : {}),
            missionModelId,
            expansionId: expansion.id,
            expansionLogic: expansion.expansionLogic,
            activityType: expansion.activityType,
          }),
        )
        .digest('hex');
      if (!typeCheckingCache.has(hash)) {
        const typeCheckResult = promiseThrottler.run(() => {
          return (
            piscina.run(
              {
                expansionLogic: expansion.expansionLogic,
                commandTypes: commandTypes,
                activityTypes: activityTypes,
                activityTypeName: expansion.activityType,
              },
              { name: 'typecheckExpansion' },
            ) as ReturnType<typeof typecheckExpansion>
          ).then(Result.fromJSON);
        });

        typeCheckingCache.set(hash, typeCheckResult);
      }
      const expansionBuildArtifacts = await typeCheckingCache.get(hash)!;

      if (expansionBuildArtifacts.isErr()) {
        return {
          activityInstance: simulatedActivity,
          commands: null,
          errors: expansionBuildArtifacts.unwrapErr(),
        };
      }

      const buildArtifacts = expansionBuildArtifacts.unwrap();

      const executionResult = Result.fromJSON(
        await (piscina.run(
          {
            serializedActivityInstance: serializeWithTemporal(simulatedActivity),
            channelData: expansionSet.parcel.channel_dictionary?.parsedJson,
            parameterData: expansionSet.parcel.parameter_dictionaries.map(
              param => param.parameter_dictionary.parsedJson,
            ),
            buildArtifacts,
          },
          { name: 'executeExpansionFromBuildArtifacts' },
        ) as ReturnType<typeof executeExpansionFromBuildArtifacts>),
      );

      if (executionResult.isErr()) {
        return {
          activityInstance: simulatedActivity,
          commands: null,
          errors: executionResult.unwrapErr(),
        };
      }

      return {
        activityInstance: simulatedActivity,
        commands: executionResult.unwrap(),
        errors: [],
      };
    }),
  );

  const rejectedExpansionResults = settledExpansionResults.filter(isRejected).map(p => p.reason);
  if (rejectedExpansionResults.length) {
    logger.error(`${rejectedExpansionResults.length} rejected expansion results`);
    console.log(rejectedExpansionResults);
  }

  for (const expansionResult of rejectedExpansionResults) {
    logger.error(expansionResult.reason);
  }
  if (rejectedExpansionResults.length > 0) {
    throw new Error(rejectedExpansionResults.map(rejectedExpansionResult => rejectedExpansionResult.reason).join('\n'));
  }

  const expandedActivityInstances = settledExpansionResults.filter(isResolved).map(p => ({
    id: p.value.activityInstance.id,
    commands: p.value.commands,
    errors: p.value.errors,
  }));

  // Store expansion run  and activity instance commands in DB
  const { rows } = await db.query(
    `
        with expansion_run_id as (
          insert into sequencing.expansion_run (simulation_dataset_id, expansion_set_id)
            values ($1, $2)
            returning id)
        insert
        into sequencing.activity_instance_commands (expansion_run_id,
                                         activity_instance_id,
                                         commands,
                                         errors)
        select *
        from unnest(
            array_fill((select id from expansion_run_id), array [array_length($3::int[], 1)]),
            $3::int[],
            $4::jsonb[],
            $5::jsonb[]
          )
        returning (select id from expansion_run_id);
      `,
    [
      simulationDatasetId,
      expansionSetId,
      expandedActivityInstances.map(result => result.id),
      expandedActivityInstances.map(result => (result.commands !== null ? JSON.stringify(result.commands) : null)),
      expandedActivityInstances.map(result => JSON.stringify(result.errors)),
    ],
  );

  if (rows.length < 1) {
    throw new Error(
      `POST /command-expansion/expand-all-activity-instances: No expansion run was inserted in the database`,
    );
  }
  const expansionRunId = rows[0].id;
  logger.info(
    `POST /command-expansion/expand-all-activity-instances: Inserted expansion run to the database: id=${expansionRunId}`,
  );

  // Get all the sequence IDs that are assigned to simulated activities.
  const seqToSimulatedActivity = await db.query(
    `
      select seq_id, simulated_activity_id
      from sequencing.sequence_to_simulated_activity
      where sequencing.sequence_to_simulated_activity.simulated_activity_id in (${pgFormat(
        '%L',
        expandedActivityInstances.map(eai => eai.id),
      )})
      and simulation_dataset_id = $1
    `,
    [simulationDatasetId],
  );

  if (seqToSimulatedActivity.rows.length > 0) {
    const seqRows = await db.query(
      `
        select metadata, seq_id, simulation_dataset_id
        from sequencing.sequence s
        where s.seq_id in (${pgFormat(
          '%L',
          seqToSimulatedActivity.rows.map(row => row.seq_id),
        )})
        and s.simulation_dataset_id = $1;
      `,
      [simulationDatasetId],
    );

    // Map seqIds to simulated activity ids so we only save expanded seqs for selected activites.
    const seqIdToSimActivityId: Record<string, Set<number>> = {};

    for (const row of seqToSimulatedActivity.rows) {
      if (seqIdToSimActivityId[row.seq_id] === undefined) {
        seqIdToSimActivityId[row.seq_id] = new Set();
      }

      seqIdToSimActivityId[row.seq_id]!.add(row.simulated_activity_id);
    }

    // If the user has created a sequence, we can try to save the expanded sequences when an expansion runs.
    for (const seqRow of seqRows.rows) {
      const seqId = seqRow.seq_id;
      const seqMetadata = seqRow.metadata;

      const simulatedActivities = await context.simulatedActivityInstanceBySimulatedActivityIdDataLoader.loadMany(
        expandedActivityInstances.map(row => ({
          simulationDatasetId,
          simulatedActivityId: row.id,
        })),
      );
      const simulatedActivitiesLoadErrors = simulatedActivities.filter(ai => ai instanceof Error);
      if (simulatedActivitiesLoadErrors.length > 0) {
        res.status(500).json({
          message: 'Error loading simulated activities',
          cause: simulatedActivitiesLoadErrors,
        });
        return next();
      }

      let sortedActivityInstances = (
        simulatedActivities as Exclude<(typeof simulatedActivities)[number], Error>[]
      ).sort((a, b) => Temporal.Duration.compare(a.startOffset, b.startOffset));

      sortedActivityInstances = sortedActivityInstances.filter(ai => seqIdToSimActivityId[seqId]?.has(ai.id));

      const sortedSimulatedActivitiesWithCommands = sortedActivityInstances.map(ai => {
        const row = expandedActivityInstances.find(row => row.id === ai.id);

        // Hasn't ever been expanded
        if (!row) {
          return {
            ...ai,
            commands: null,
            errors: null,
          };
        }

        const errors = row.errors as unknown;

        return {
          ...ai,
          commands:
            row.commands?.map(c => {
              switch (c.type) {
                case 'command':
                  return CommandStem.fromSeqJson(c);
                case 'load':
                  return LoadStep.fromSeqJson(c);
                case 'activate':
                  return ActivateStep.fromSeqJson(c);
                default:
                  throw new Error(`Unknown command type: ${c.type}`);
              }
            }) ?? null,
          errors: errors as { message: string; stack: string; location: { line: number; column: number } }[],
        };
      });

      // This is here to easily enable a future feature of allowing the mission to configure their own sequence
      // building. For now, we just use the 'seqJsonBuilder' until such a feature request is made.
      const seqBuilder = seqJsonBuilder;
      const sequence = seqBuilder(sortedSimulatedActivitiesWithCommands, seqId, seqMetadata, simulationDatasetId);

      const { rows } = await db.query(
        `
          insert into sequencing.expanded_sequences (expansion_run_id, seq_id, simulation_dataset_id, expanded_sequence)
            values ($1, $2, $3, $4)
            returning id
      `,
        [expansionRunId, seqId, simulationDatasetId, sequence.toSeqJson()],
      );

      if (rows.length < 1) {
        throw new Error(
          `POST /command-expansion/expand-all-activity-instances: No expanded sequences were inserted into the database`,
        );
      }
      const expandedSequenceId = rows[0].id;
      logger.info(
        `POST /command-expansion/expand-all-activity-instances: Inserted expanded sequence to the database: id=${expandedSequenceId}`,
      );
    }
  }

  res.status(200).json({
    id: expansionRunId,
    expandedActivityInstances,
  });
  return next();
});
