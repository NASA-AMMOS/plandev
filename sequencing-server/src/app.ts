import bodyParser from 'body-parser';
import DataLoader from 'dataloader';
import express, { Application, NextFunction, Request, Response } from 'express';
import { GraphQLClient } from 'graphql-request';
import fs from 'node:fs';
import { Status } from './common.js';
import { DbExpansion } from './db.js';
import { getEnv } from './env.js';
import { activitySchemaBatchLoader } from './lib/batchLoaders/activitySchemaBatchLoader.js';
import { commandDictionaryTypescriptBatchLoader } from './lib/batchLoaders/commandDictionaryTypescriptBatchLoader.js';
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
import { PluginManager } from './utils/PluginManager.js';
import { DictionaryType } from './types/types.js';
import type { ChannelDictionary, CommandDictionary, ParameterDictionary } from '@nasa-jpl/plandev-ampcs';
import { sequenceTemplateBatchLoader } from './lib/batchLoaders/sequenceTemplateBatchLoader.js';
import { sequenceFilterBatchLoader } from './lib/batchLoaders/sequenceFilterBatchLoader.js';
import { writeFile } from './utils/file.js';
import { randomBytes } from 'node:crypto';

const logger = getLogger('app');
const PORT: number = parseInt(getEnv().PORT, 10) ?? 27184;

const healthCheckApp: Application = express();
healthCheckApp.get('/', (_: Request, res: Response) => {
  res.send('Aerie Sequencing Service');
});

healthCheckApp.get('/health', (_: Request, res: Response) => {
  res.status(200).send();
});

const healthCheckServer = healthCheckApp.listen(8080);

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

export const pluginManager = new PluginManager();

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
  simulatedActivityInstanceBySeqIdBatchLoader: InferredDataloader<typeof simulatedActivityInstanceBySeqIdBatchLoader>;
  sequenceFilterDataLoader: InferredDataloader<typeof sequenceFilterBatchLoader>;
  sequenceTemplateDataLoader: InferredDataloader<typeof sequenceTemplateBatchLoader>;
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
        graphqlClient,
      }),
    ),
    sequenceFilterDataLoader: new DataLoader(
      sequenceFilterBatchLoader({
        graphqlClient,
      }),
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
  const persistDictionaryToFilesystem = req.body.input.persistDictionaryToFilesystem as boolean;
  logger.info(`Dictionary received`);

  let parsedDictionaries: {
    commandDictionary?: CommandDictionary;
    channelDictionary?: ChannelDictionary;
    parameterDictionary?: ParameterDictionary;
  };
  if (
    pluginManager.hasPlugin(getEnv().DICTIONARY_PARSER_PLUGIN) &&
    pluginManager.getPlugin(getEnv().DICTIONARY_PARSER_PLUGIN).parseDictionary
  ) {
    parsedDictionaries = pluginManager.getPlugin(getEnv().DICTIONARY_PARSER_PLUGIN).parseDictionary(dictionary);
  } else {
    throw new Error(
      `POST /dictionary: Plugin - ${getEnv().DICTIONARY_PARSER_PLUGIN} \ndoesn't have a 'parseDictionary' method`,
    );
  }

  let json = {};
  for (const dictionaryType of Object.keys(DictionaryType)) {
    let parsedDictionary: CommandDictionary | ParameterDictionary | ChannelDictionary | undefined;
    let db_table_name = '';
    let db_value: any[] = [];

    if (dictionaryType == DictionaryType.COMMAND && parsedDictionaries.commandDictionary) {
      db_table_name = 'command_dictionary';
      parsedDictionary = parsedDictionaries.commandDictionary as CommandDictionary;
    } else if (dictionaryType == DictionaryType.CHANNEL && parsedDictionaries.channelDictionary) {
      db_table_name = 'channel_dictionary';
      parsedDictionary = parsedDictionaries.channelDictionary as ChannelDictionary;
    } else if (dictionaryType == DictionaryType.PARAMETER && parsedDictionaries.parameterDictionary) {
      db_table_name = 'parameter_dictionary';
      parsedDictionary = parsedDictionaries.parameterDictionary as ParameterDictionary;
    }
    if (!parsedDictionary) {
      continue;
    }

    const dictionaryPath = await processDictionary(parsedDictionary, dictionaryType as DictionaryType);
    let dictionaryFilePath = undefined;

    if (persistDictionaryToFilesystem) {
      dictionaryFilePath = await writeFile(
        `${randomBytes(20).toString('hex')}`,
        parsedDictionary.header.mission_name.toLowerCase(),
        dictionary,
      );
    }

    logger.info(`lib generated - path: ${dictionaryPath}`);
    db_value = [
      dictionaryPath,
      parsedDictionary.header.mission_name,
      parsedDictionary.header.version,
      parsedDictionary,
      dictionaryFilePath,
    ];

    const sqlExpression = `
      insert into sequencing.${db_table_name} (dictionary_path, mission, version, parsed_json, dictionary_file_path)
      values ($1, $2, $3, $4, $5)
      on conflict (mission, version) do update
        set dictionary_path = $1, parsed_json = $4, dictionary_file_path = $5
      returning id, dictionary_path, mission, version, parsed_json, created_at, dictionary_file_path;
    `;
    const { rows } = await db.query(sqlExpression, db_value);
    if (rows.length < 1) {
      throw new Error(`POST /dictionary: No dictionary was updated in the database`);
    }
    json = { ...json, ...{ [dictionaryType.toLowerCase()]: rows[0] } };
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

const appServer = app.listen(PORT, () => {
  logger.info(`connected to port ${PORT}`);

  pluginManager.loadPlugins();
});

appServer.once('close', () => healthCheckServer.close());
