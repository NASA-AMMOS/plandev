import bodyParser from 'body-parser';
import DataLoader from 'dataloader';
import express, { Application, NextFunction, Request, Response } from 'express';
import { GraphQLClient } from 'graphql-request';
import fs from 'node:fs';
import Piscina from 'piscina';
import { Status } from './common.js';
import { DbExpansion } from './db.js';
import { getEnv } from './env.js';
import { activitySchemaBatchLoader } from './lib/batchLoaders/activitySchemaBatchLoader.js';
import { commandDictionaryTypescriptBatchLoader } from './lib/batchLoaders/commandDictionaryTypescriptBatchLoader.js';
import { expansionBatchLoader } from './lib/batchLoaders/expansionBatchLoader.js';
import { expansionSetBatchLoader } from './lib/batchLoaders/expansionSetBatchLoader.js';
import { parcelBatchLoader } from './lib/batchLoaders/parcelBatchLoader.js';
import { InferredDataloader, objectCacheKeyFunction } from './lib/batchLoaders/index.js';
import {
  simulatedActivitiesBatchLoader,
  simulatedActivityInstanceBySeqIdBatchLoader,
  simulatedActivityInstanceBySimulatedActivityIdBatchLoader,
} from './lib/batchLoaders/simulatedActivityBatchLoader.js';
import { generateTypescriptForGraphQLActivitySchema } from './lib/codegen/ActivityTypescriptCodegen.js';
import { processDictionary } from './lib/codegen/CommandTypeCodegen.js';
import './polyfills.js';
import getLogger from './utils/logger.js';
import { commandExpansionRouter } from './routes/command-expansion.js';
import { seqjsonRouter } from './routes/seqjson.js';
import { getHasuraSession, canUserPerformAction, ENDPOINTS_WHITELIST } from './utils/hasura.js';
import type { Result } from '@nasa-jpl/aerie-ts-user-code-runner/build/utils/monads';
import type { CacheItem, UserCodeError } from '@nasa-jpl/aerie-ts-user-code-runner';
import { PromiseThrottler } from './utils/PromiseThrottler.js';
import { backgroundTranspiler } from './backgroundTranspiler.js';
import { PluginManager } from './utils/PluginManager.js'
import { DictionaryType } from './types/types.js';
import type { ChannelDictionary, CommandDictionary, ParameterDictionary } from '@nasa-jpl/aerie-ampcs';
import { sequenceTemplateBatchLoader } from './lib/batchLoaders/sequenceTemplateBatchLoader.js';

const logger = getLogger('app');
const PORT: number = parseInt(getEnv().PORT, 10) ?? 27184;

logger.info(`Starting sequencing-server app on Node v${process.versions.node}...`);

const app: Application = express();
// WARNING: bodyParser.json() is vulnerable to a string too long issue. Iff that occurs,
// we should switch to a stream parser like https://www.npmjs.com/package/stream-json
app.use(bodyParser.json({ limit: '100mb' }));

DbExpansion.init();
export const db = DbExpansion.getDb();
export let graphqlClient = new GraphQLClient(getEnv().MERLIN_GRAPHQL_URL, {
  headers: { 'x-hasura-admin-secret': getEnv().HASURA_GRAPHQL_ADMIN_SECRET },
});
export const piscina = new Piscina({
  filename: new URL('worker.js', import.meta.url).pathname,
  minThreads: parseInt(getEnv().SEQUENCING_WORKER_NUM),
  maxThreads: parseInt(getEnv().SEQUENCING_MAX_WORKER_NUM),
  resourceLimits: { maxOldGenerationSizeMb: parseInt(getEnv().SEQUENCING_MAX_WORKER_HEAP_MB) },
});
export const promiseThrottler = new PromiseThrottler(parseInt(getEnv().SEQUENCING_WORKER_NUM) - 2);
export const pluginManager = new PluginManager();
export const typeCheckingCache = new Map<string, Promise<Result<CacheItem, ReturnType<UserCodeError['toJSON']>[]>>>();

const temporalPolyfillTypes = fs.readFileSync(
  new URL('./types/TemporalPolyfillTypes.ts', import.meta.url).pathname,
  'utf-8',
);
const channelDictionaryTypes: string = fs.readFileSync(
  new URL('./types/ChannelTypes.ts', import.meta.url).pathname,
  'utf-8',
);
const parameterDictionaryTypes: string = fs.readFileSync(
  new URL('./types/ParameterTypes.ts', import.meta.url).pathname,
  'utf-8',
);

export type Context = {
  commandTypescriptDataLoader: InferredDataloader<typeof commandDictionaryTypescriptBatchLoader>;
  activitySchemaDataLoader: InferredDataloader<typeof activitySchemaBatchLoader>;
  simulatedActivitiesDataLoader: InferredDataloader<typeof simulatedActivitiesBatchLoader>;
  simulatedActivityInstanceBySimulatedActivityIdDataLoader: InferredDataloader<
    typeof simulatedActivityInstanceBySimulatedActivityIdBatchLoader
  >;
  simulatedActivityInstanceBySeqIdBatchLoader: InferredDataloader<
    typeof simulatedActivityInstanceBySeqIdBatchLoader
  >;
  sequenceTemplateDataLoader: InferredDataloader<typeof sequenceTemplateBatchLoader>;
  expansionSetDataLoader: InferredDataloader<typeof expansionSetBatchLoader>;
  expansionDataLoader: InferredDataloader<typeof expansionBatchLoader>;
  parcelTypescriptDataLoader: InferredDataloader<typeof parcelBatchLoader>;
};

app.use(async (req: Request, res: Response, next: NextFunction) => {
  // Check and make sure the user making the request has the required permissions.
  if (
    !ENDPOINTS_WHITELIST.has(req.url) &&
    !(await canUserPerformAction(
      req.url,
      graphqlClient,
      getHasuraSession(req.body.session_variables, req.headers.authorization),
      req.body,
    ))
  ) {
    throw new Error(`You do not have sufficient permissions to perform this action.`);
  }

  const activitySchemaDataLoader = new DataLoader(activitySchemaBatchLoader({ graphqlClient }), {
    cacheKeyFn: objectCacheKeyFunction,
    name: null,
  });

  res.locals['context'] = {
    commandTypescriptDataLoader: new DataLoader(commandDictionaryTypescriptBatchLoader({ graphqlClient }), {
      cacheKeyFn: objectCacheKeyFunction,
      name: null,
    }),
    activitySchemaDataLoader,
    simulatedActivitiesDataLoader: new DataLoader(
      simulatedActivitiesBatchLoader({
        graphqlClient,
        activitySchemaDataLoader,
      }),
      {
        cacheKeyFn: objectCacheKeyFunction,
        name: null,
      },
    ),
    sequenceTemplateDataLoader: new DataLoader(
      sequenceTemplateBatchLoader({
        graphqlClient
      })
    ),
    simulatedActivityInstanceBySimulatedActivityIdDataLoader: new DataLoader(
      simulatedActivityInstanceBySimulatedActivityIdBatchLoader({
        graphqlClient,
        activitySchemaDataLoader,
      }),
      {
        cacheKeyFn: objectCacheKeyFunction,
        name: null,
      },
    ),
    simulatedActivityInstanceBySeqIdBatchLoader: new DataLoader(
      simulatedActivityInstanceBySeqIdBatchLoader({
        graphqlClient,
        activitySchemaDataLoader,
      }),
      {
        cacheKeyFn: objectCacheKeyFunction,
        name: null,
      },
    ),
    expansionSetDataLoader: new DataLoader(expansionSetBatchLoader({ graphqlClient }), {
      cacheKeyFn: objectCacheKeyFunction,
      name: null,
    }),
    expansionDataLoader: new DataLoader(expansionBatchLoader({ graphqlClient }), {
      cacheKeyFn: objectCacheKeyFunction,
      name: null,
    }),
    parcelTypescriptDataLoader: new DataLoader(parcelBatchLoader({ graphqlClient }), {
      cacheKeyFn: objectCacheKeyFunction,
      name: null,
    }),
  } as Context;
  return next();
});

app.use('/command-expansion', commandExpansionRouter);
app.use('/seqjson', seqjsonRouter);

app.get('/', (_: Request, res: Response) => {
  res.send('Aerie Sequencing Service');
});

app.get('/health', (_: Request, res: Response) => {
  res.status(200).send();
});

app.post('/put-dictionary', async (req, res, next) => {
  const dictionary = req.body.input.dictionary as string;
  logger.info(`Dictionary received`);

  let parsedDictionaries : {
    commandDictionary?: CommandDictionary,
    channelDictionary?: ChannelDictionary,
    parameterDictionary?: ParameterDictionary
  };
  if (pluginManager.hasPlugin(getEnv().DICTIONARY_PARSER_PLUGIN) && pluginManager.getPlugin(getEnv().DICTIONARY_PARSER_PLUGIN).parseDictionary){
    parsedDictionaries = pluginManager.getPlugin(getEnv().DICTIONARY_PARSER_PLUGIN).parseDictionary(dictionary)
  } else {
    throw new Error(`POST /dictionary: Plugin - ${getEnv().DICTIONARY_PARSER_PLUGIN} \ndoesn't have a 'parseDictionary' method`);
  }

  let json = {}
  for (const dictionaryType of Object.keys(DictionaryType)) {
    let parsedDictionary : CommandDictionary | ParameterDictionary | ChannelDictionary | undefined;
    let db_table_name = "";
    let db_value : any[] = []
    if (dictionaryType == DictionaryType.COMMAND && parsedDictionaries.commandDictionary) {
      db_table_name = 'command_dictionary'
      parsedDictionary = parsedDictionaries.commandDictionary as CommandDictionary
    }else if (dictionaryType == DictionaryType.CHANNEL && parsedDictionaries.channelDictionary) {
      db_table_name = 'channel_dictionary'
      parsedDictionary = parsedDictionaries.channelDictionary as ChannelDictionary
    }else if (dictionaryType == DictionaryType.PARAMETER && parsedDictionaries.parameterDictionary) {
      db_table_name = 'parameter_dictionary'
      parsedDictionary = parsedDictionaries.parameterDictionary as ParameterDictionary
    }
    if (!parsedDictionary){
      continue;
    }

    const dictionaryPath = await processDictionary(parsedDictionary, dictionaryType as DictionaryType);
    logger.info(`lib generated - path: ${dictionaryPath}`);
    db_value = [
      dictionaryPath,
      parsedDictionary.header.mission_name,
      parsedDictionary.header.version,
      parsedDictionary,
    ]
    const sqlExpression = `
      insert into sequencing.${db_table_name} (dictionary_path, mission, version, parsed_json)
      values ($1, $2, $3, $4)
      on conflict (mission, version) do update
        set dictionary_path = $1, parsed_json = $4
      returning id, dictionary_path, mission, version, parsed_json, created_at;
    `;
    const { rows } = await db.query(sqlExpression, db_value);
    if (rows.length < 1) {
      throw new Error(`POST /dictionary: No command dictionary was updated in the database`);
    }
    json = {...json, ...{[dictionaryType.toLowerCase()] : rows[0]}}
  }

  res.status(200).json(json);
  return next();
});

app.post('/get-command-typescript', async (req, res, next) => {
  const context: Context = res.locals['context'];

  const commandDictionaryId = req.body.input.commandDictionaryId as number;

  try {
    const commandTypescript = await context.commandTypescriptDataLoader.load({ dictionaryId: commandDictionaryId });

    res.status(200).json({
      status: Status.SUCCESS,
      typescriptFiles: [
        {
          filePath: 'command-types.ts',
          content: commandTypescript,
        },
        {
          filePath: 'TemporalPolyfillTypes.ts',
          content: temporalPolyfillTypes,
        },
        {
          filePath: 'ChannelTypes.ts',
          content: channelDictionaryTypes,
        },
        {
          filePath: 'ParameterTypes.ts',
          content: parameterDictionaryTypes,
        },
      ],
      reason: null,
    });
  } catch (e) {
    res.status(200).json({
      status: Status.FAILURE,
      typescriptFiles: null,
      reason: (e as Error).message,
    });
  }
  return next();
});

app.post('/get-activity-typescript', async (req, res, next) => {
  const context: Context = res.locals['context'];

  const missionModelId = req.body.input.missionModelId as number;
  const activityTypeName = req.body.input.activityTypeName as string;

  const activitySchema = await context.activitySchemaDataLoader.load({ missionModelId, activityTypeName });
  const activityTypescript = generateTypescriptForGraphQLActivitySchema(activitySchema);

  res.status(200).json({
    status: Status.SUCCESS,
    typescriptFiles: [
      {
        filePath: 'activity-types.ts',
        content: activityTypescript,
      },
      {
        filePath: 'TemporalPolyfillTypes.ts',
        content: temporalPolyfillTypes,
      },
    ],
    reason: null,
  });
  return next();
});

// General error handler
app.use((err: any, _: Request, res: Response, next: NextFunction) => {
  logger.error(err);

  res.status(err.status ?? err.statusCode ?? 500).send({
    message: err.message,
    extensions: {
      ...(err.cause ? { cause: err.cause } : {}),
      ...(err.stack ? { stack: err.stack } : {}),
      object: err,
    },
  });
  return next();
});

app.listen(PORT, () => {
  logger.info(`connected to port ${PORT}`);
  logger.info(`Worker pool initialized:
              Total workers started: ${piscina.threads.length},
              Max Workers Allowed: ${getEnv().SEQUENCING_MAX_WORKER_NUM},
              Heap Size per Worker: ${getEnv().SEQUENCING_MAX_WORKER_HEAP_MB} MB`);

  pluginManager.loadPlugins();

  if (getEnv().TRANSPILER_ENABLED === 'true') {
    //log that the tranpiler is on
    logger.info(`Background Transpiler is 'on'`);

    let transpilerPromise: Promise<void> | undefined; // Holds the transpilation promise
    async function invokeTranspiler() {
      try {
        await backgroundTranspiler();
      } catch (error) {
        console.error('Error during transpilation:', error);
      } finally {
        transpilerPromise = undefined; // Reset promise after completion
      }
    }

    // Immediately call the background transpiler
    transpilerPromise = invokeTranspiler();

    // Schedule next execution after 2 minutes, handling ongoing transpilation
    setInterval(async () => {
      if (!transpilerPromise) {
        transpilerPromise = invokeTranspiler(); // Start a new transpilation
      }
    }, 60 * 2 * 1000);
  }
});
