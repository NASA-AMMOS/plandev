import { piscina, graphqlClient, typeCheckingCache, promiseThrottler } from './app.js';
import { objectCacheKeyFunction } from './lib/batchLoaders/index.js';
import crypto from 'crypto';
import { generateTypescriptForGraphQLActivitySchema } from './lib/codegen/ActivityTypescriptCodegen.js';
import DataLoader from 'dataloader';
import { activitySchemaBatchLoader } from './lib/batchLoaders/activitySchemaBatchLoader.js';
import { commandDictionaryTypescriptBatchLoader } from './lib/batchLoaders/commandDictionaryTypescriptBatchLoader.js';
import { parcelBatchLoader } from './lib/batchLoaders/parcelBatchLoader.js';
import type { typecheckExpansion } from './worker';
import { Result } from '@nasa-jpl/plandev-ts-user-code-runner/build/utils/monads.js';
import { getLatestParcel, getLatestMissionModel, getExpansionRule } from './utils/hasura.js';
import type { Parcel } from './lib/batchLoaders/parcelBatchLoader.js';
import getLogger from './utils/logger.js';

const logger = getLogger('[ Background Transpiler ]');

export async function backgroundTranspiler(numberOfThreads: number = 2) {
  if (graphqlClient === null) {
    return;
  }

  // Fetch latest mission model
  const {
    mission_model_aggregate: {
      aggregate: {
        max: { id: missionModelId },
      },
    },
  } = await getLatestMissionModel(graphqlClient);
  if (!missionModelId) {
    logger.warn('Unable to fetch the latest mission model. Aborting background transpiling...');
    return;
  }

  // Fetch latest command dictionary
  const {
    parcel_aggregate: {
      aggregate: {
        max: { id: parcelID },
      },
    },
  } = await getLatestParcel(graphqlClient);
  if (!parcelID) {
    logger.warn('Unable to fetch the latest command dictionary. Aborting background transpiling...');
    return;
  }

  const { expansion_rule } = await getExpansionRule(graphqlClient, missionModelId, parcelID);

  if (expansion_rule === null || expansion_rule.length === 0) {
    logger.info(`No expansion rules to transpile.`);
    return;
  }

  const commandTypescriptDataLoader = new DataLoader(commandDictionaryTypescriptBatchLoader({ graphqlClient }), {
    cacheKeyFn: objectCacheKeyFunction,
    name: null,
  });
  const activitySchemaDataLoader = new DataLoader(activitySchemaBatchLoader({ graphqlClient }), {
    cacheKeyFn: objectCacheKeyFunction,
    name: null,
  });
  const parcelTypescriptDataLoader = new DataLoader(parcelBatchLoader({ graphqlClient }), {
    cacheKeyFn: objectCacheKeyFunction,
    name: null,
  });

  const parcel: Parcel = await parcelTypescriptDataLoader.load({
    parcelId: parcelID,
  });

  if (parcel === null) {
    logger.error(`Unable to fetch parcel.\nAborting transpiling...`);
    return;
  }

  const commandTypes = await commandTypescriptDataLoader.load({
    dictionaryId: parcel.command_dictionary.id,
  });

  if (commandTypes === null) {
    logger.error(`Unable to fetch command ts lib.\nAborting transpiling...`);
    return;
  }

  // only process 'numberOfThreads' worth at a time ex. transpile 2 logics at a time
  // This allows for expansion set and sequence expansion to utilize the remaining workers
  for (let i = 0; i < expansion_rule.length; i += numberOfThreads) {
    await Promise.all(
      expansion_rule.slice(i, i + numberOfThreads).map(async (expansion, j) => {
        await promiseThrottler.run(async () => {
          // Assuming expansion_rule elements have the same type
          if (expansion instanceof Error) {
            logger.error(`Expansion: ${expansion.name} could not be loaded`, expansion);
            return Promise.reject(`Expansion: ${expansion.name} could not be loaded`);
          }

          const hash = crypto
            .createHash('sha256')
            .update(
              JSON.stringify({
                parcelID: parcel.id,
                commandDictionaryId: parcel.command_dictionary.id,
                parameterDictionaryId: parcel.parameter_dictionaries.map(parm => parm.parameter_dictionary.id),
                ...(parcel.channel_dictionary ? { channelDictionaryId: parcel.channel_dictionary.id } : {}),
                missionModelId,
                expansionId: expansion.id,
                expansionLogic: expansion.expansion_logic,
                activityType: expansion.activity_type,
              }),
            )
            .digest('hex');

          // ignore already transpiled hash info
          if (typeCheckingCache.has(hash)) {
            return Promise.resolve();
          }

          const activitySchema = await activitySchemaDataLoader.load({
            missionModelId,
            activityTypeName: expansion.activity_type,
          });

          // log error
          if (!activitySchema) {
            const msg = `Activity schema for ${expansion.activity_type} could not be loaded`;
            logger.error(msg, activitySchema);
            return Promise.reject(msg);
          }

          const activityTypescript = generateTypescriptForGraphQLActivitySchema(activitySchema);

          // log error
          if (!activityTypescript) {
            const msg = `Unable to generate typescript for activity ${expansion.activity_type}`;
            logger.error(msg, activityTypescript);
            return Promise.reject(msg);
          }

          const progress = `(${i + j + 1} of ${expansion_rule.length})`;
          logger.info(`Assigning worker to typecheck ${expansion.activity_type} ${progress}`);
          const typecheckingResult = (
            piscina.run(
              {
                expansionLogic: expansion.expansion_logic,
                commandTypes: commandTypes,
                activityTypes: activityTypescript,
                activityTypeName: expansion.activity_type,
              },
              { name: 'typecheckExpansion' },
            ) as ReturnType<typeof typecheckExpansion>
          ).then(Result.fromJSON);

          //Display any errors
          typecheckingResult.then(result => {
            if (result.isErr()) {
              logger.error(`Error transpiling ${expansion.activity_type}:\n ${result.unwrapErr().map(e => e.message)}`);
            }
          });

          typeCheckingCache.set(hash, typecheckingResult);
          return typecheckingResult;
        });
      }),
    );
  }
}
