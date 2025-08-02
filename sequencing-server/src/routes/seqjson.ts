import type { UserCodeError } from '@nasa-jpl/aerie-ts-user-code-runner';
import { Context, db } from './../app.js';
import express from 'express';
import pgFormat from 'pg-format';
import { seqJsonBuilder } from '../builders/seqJsonBuilder.js';
// @ts-ignore
import schema from '@nasa-jpl/seq-json-schema/schema.json' assert { type: 'json' };
import { ActivateStep, LoadStep } from './../lib/codegen/CommandEDSLPreface.js';
import { CommandStem } from './../lib/codegen/CommandEDSLPreface.js';
import type { Command, Activate, Load } from '@nasa-jpl/seq-json-schema/types';
import { FallibleStatus } from '../types/types.js';
import { assertDefined, assertOne } from './../utils/assertions.js';
import { isResolved } from './../utils/typeguards.js';

export const seqjsonRouter = express.Router();

/**
 * Get the sequence JSON for a sequence based on seqid and simulation dataset id
 *
 * @deprecated Use `/bulk-get-seqjson-for-seqid-and-simulation-dataset` instead
 */
seqjsonRouter.post('/get-seqjson-for-seqid-and-simulation-dataset', async (req, res, next) => {
  // Get the specified sequence + activity instance ids + commands from the latest expansion run for each activity instance (filtered on simulation dataset)
  // get start time for each activity instance and join with activity instance command data
  // Create sequence object with all the commands and return the seqjson
  const context: Context = res.locals['context'];

  const seqId = req.body.input.seqId as string;
  const simulationDatasetId = req.body.input.simulationDatasetId as number;

  const [{ rows: activityInstanceCommandRows }, { rows: seqRows }] = await Promise.all([
    db.query<{
      metadata: Record<string, unknown>;
      commands: (Command | Activate | Load)[];
      activity_instance_id: number;
      errors: ReturnType<UserCodeError['toJSON']>[] | null;
    }>(
      `
        with joined_table as (
          select  aic.commands,
                  aic.activity_instance_id,
                  aic.errors,
                  aic.expansion_run_id
          from sequencing.sequence_to_simulated_activity ssa
          join sequencing.activity_instance_commands aic
            on ssa.simulated_activity_id = aic.activity_instance_id
          where (ssa.simulation_dataset_id, ssa.seq_id) = ($1, $2)),
          max_values as (
            select
              activity_instance_id,
              max(expansion_run_id) as max_expansion_run_id
            from joined_table
            group by activity_instance_id
          )
        select joined_table.commands,
                joined_table.activity_instance_id,
                joined_table.errors
        from joined_table,
              max_values
        where joined_table.activity_instance_id = max_values.activity_instance_id
          and joined_table.expansion_run_id = max_values.max_expansion_run_id;
      `,
      [simulationDatasetId, seqId],
    ),
    db.query<{
      metadata: Record<string, any>;
    }>(
      `
        select metadata
        from sequencing.sequence s
        where s.seq_id = $2
          and s.simulation_dataset_id = $1;
      `,
      [simulationDatasetId, seqId],
    ),
  ]);

  const seqMetadata = assertOne(
    seqRows,
    `No sequence found with seq_id: ${seqId} and simulation_dataset_id: ${simulationDatasetId}`,
  ).metadata;

  const simulatedActivities = await context.simulatedActivityInstanceBySimulatedActivityIdDataLoader.loadMany(
    activityInstanceCommandRows.map(row => ({
      simulationDatasetId,
      simulatedActivityId: row.activity_instance_id,
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

  const sortedActivityInstances = (simulatedActivities as Exclude<(typeof simulatedActivities)[number], Error>[]).sort(
    (a, b) => Temporal.Duration.compare(a.startOffset, b.startOffset),
  );

  const sortedSimulatedActivitiesWithCommands = sortedActivityInstances.map(ai => {
    const row = activityInstanceCommandRows.find(row => row.activity_instance_id === ai.id);
    // Hasn't ever been expanded
    if (!row) {
      return {
        ...ai,
        expansionResult: null,
        errors: null,
      };
    }
    return {
      ...ai,
      expansionResult:
        row.commands?.map(c => {
          switch (c.type) {
            case 'command':
              return CommandStem.fromSeqJson(c);
            case 'load':
              return LoadStep.fromSeqJson(c);
            case 'activate':
              return ActivateStep.fromSeqJson(c);
            default:
              throw new Error(`Unknown command type: ${c}`);
          }
        }) ?? null,
      errors: row.errors,
    };
  });

  const errors = sortedSimulatedActivitiesWithCommands.flatMap(ai => ai.errors ?? []);

  // This is here to easily enable a future feature of allowing the mission to configure their own sequence
  // building. For now, we just use the 'defaultSeqBuilder' until such a feature request is made.
  const seqBuilder = seqJsonBuilder; 
  const sequenceJson = seqBuilder(
    sortedSimulatedActivitiesWithCommands,
    seqId,
    seqMetadata,
    simulationDatasetId,
  ).toSeqJson();

  if (errors.length > 0) {
    res.json({
      status: FallibleStatus.FAILURE,
      seqJson: sequenceJson,
      errors,
    });
  } else {
    res.json({
      status: FallibleStatus.SUCCESS,
      seqJson: sequenceJson,
      errors,
    });
  }
  return next();
});

seqjsonRouter.post('/bulk-get-seqjson-for-seqid-and-simulation-dataset', async (req, res, next) => {
  // Get the specified sequence + activity instance ids + commands from the latest expansion run for each activity instance (filtered on simulation dataset)
  // get start time for each activity instance and join with activity instance command data
  // Create sequence object with all the commands and return the seqjson
  const context: Context = res.locals['context'];

  const inputs = req.body.input.inputs as { seqId: string; simulationDatasetId: number }[];

  const inputTuples = inputs.map(input => [input.seqId, input.simulationDatasetId] as [string, number]);

  // Grab all the activity instance commands and sequence metadata for the specified sequences
  const [{ rows: activityInstanceCommandRows }, { rows: seqRows }] = await Promise.all([
    db.query<{
      metadata: Record<string, unknown>;
      commands: (Command | Activate | Load)[];
      activity_instance_id: number;
      errors: ReturnType<UserCodeError['toJSON']>[] | null;
      seq_id: string;
      simulation_dataset_id: number;
    }>(
      `
        with
        joined_table as (
          select
            aic.commands,
            aic.activity_instance_id,
            aic.errors,
            aic.expansion_run_id,
            ssa.seq_id,
            ssa.simulation_dataset_id
          from sequencing.sequence_to_simulated_activity ssa
          join sequencing.activity_instance_commands aic
            on ssa.simulated_activity_id = aic.activity_instance_id
          where (ssa.seq_id, ssa.simulation_dataset_id) in (${pgFormat('%L', inputTuples)})
        ),
        max_values as (
          select
            activity_instance_id,
            max(expansion_run_id) as max_expansion_run_id
          from joined_table
          group by activity_instance_id
        )
        select
          joined_table.commands,
          joined_table.activity_instance_id,
          joined_table.errors,
          joined_table.seq_id,
          joined_table.simulation_dataset_id
        from
          joined_table,
          max_values
        where joined_table.activity_instance_id = max_values.activity_instance_id
          and joined_table.expansion_run_id = max_values.max_expansion_run_id;
      `,
    ),
    db.query<{
      seq_id: string;
      simulation_dataset_id: number;
      metadata: Record<string, any>;
    }>(
      `
        select metadata, seq_id, simulation_dataset_id
        from sequencing.sequence s
        where (s.seq_id, s.simulation_dataset_id) in (${pgFormat('%L', inputTuples)});
      `,
    ),
  ]);

  // This is here to easily enable a future feature of allowing the mission to configure their own sequence
  // building. For now, we just use the 'defaultSeqBuilder' until such a feature request is made.
  const seqBuilder = seqJsonBuilder; // TODO: make this compatible with strings too. also...why do we even have this? why not just pull from or update expanded sequences???

  const promises = await Promise.allSettled(
    inputs.map(async ({ seqId, simulationDatasetId }) => {
      const activityInstanceCommandRowsForSeq = activityInstanceCommandRows.filter(
        row => row.seq_id === seqId && row.simulation_dataset_id === simulationDatasetId,
      );
      const seqRowsForSeq = seqRows.find(
        row => row.seq_id === seqId && row.simulation_dataset_id === simulationDatasetId,
      );

      const seqMetadata = assertDefined(
        seqRowsForSeq,
        `No sequence found with seq_id: ${seqId} and simulation_dataset_id: ${simulationDatasetId}`,
      ).metadata;

      const simulatedActivitiesForSeqId =
        await context.simulatedActivityInstanceBySimulatedActivityIdDataLoader.loadMany(
          activityInstanceCommandRowsForSeq.map(row => ({
            simulationDatasetId,
            simulatedActivityId: row.activity_instance_id,
          })),
        );

      const simulatedActivitiesLoadErrors = simulatedActivitiesForSeqId.filter(ai => ai instanceof Error);

      if (simulatedActivitiesLoadErrors.length > 0) {
        throw new Error(
          `Error loading simulated activities for seqId: ${seqId}, simulationDatasetId: ${simulationDatasetId}`,
          { cause: simulatedActivitiesLoadErrors },
        );
      }

      const sortedActivityInstances = (
        simulatedActivitiesForSeqId as Exclude<(typeof simulatedActivitiesLoadErrors)[number], Error>[]
      ).sort((a, b) => Temporal.Instant.compare(a.startTime, b.startTime));

      const sortedSimulatedActivitiesWithCommands = sortedActivityInstances.map(ai => {
        const row = activityInstanceCommandRows.find(row => row.activity_instance_id === ai.id);
        // Hasn't ever been expanded
        if (!row) {
          return {
            ...ai,
            expansionResult: null,
            errors: null,
          };
        }
        return {
          ...ai,
          expansionResult:
            row.commands?.map(c => {
              switch (c.type) {
                case 'command':
                  return CommandStem.fromSeqJson(c);
                case 'load':
                  return LoadStep.fromSeqJson(c);
                case 'activate':
                  return ActivateStep.fromSeqJson(c);
                default:
                  throw new Error(`Unknown command type: ${c}`);
              }
            }) ?? null,
          errors: row.errors,
        };
      });

      const errors = sortedSimulatedActivitiesWithCommands.flatMap(ai => ai.errors ?? []);

      const sequenceJson = seqBuilder(
        sortedSimulatedActivitiesWithCommands,
        seqId,
        seqMetadata,
        simulationDatasetId,
      ).toSeqJson();

      if (errors.length > 0) {
        return {
          status: FallibleStatus.FAILURE,
          seqJson: sequenceJson,
          errors,
        };
      } else {
        return {
          status: FallibleStatus.SUCCESS,
          seqJson: sequenceJson,
          errors: [],
        };
      }
    }),
  );

  res.json(
    promises.map(promise => {
      if (isResolved(promise)) {
        return promise.value;
      } else {
        return {
          status: FallibleStatus.FAILURE,
          seqJson: null,
          errors: [promise.reason],
        };
      }
    }),
  );

  return next();
});
