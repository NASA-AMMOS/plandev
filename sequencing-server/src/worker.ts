import './polyfills.js';

import * as fs from 'node:fs';
import vm from 'node:vm';
import { threadId } from 'worker_threads';

import { CacheItem, UserCodeError, UserCodeRunner } from '@nasa-jpl/aerie-ts-user-code-runner';
import { Result, SerializedResult } from '@nasa-jpl/aerie-ts-user-code-runner/build/utils/monads.js';
import type { ChannelDictionary, ParameterDictionary } from '@nasa-jpl/plandev-ampcs';
import type { SeqJson } from '@nasa-jpl/seq-json-schema/types';
import ts from 'typescript';
import type { SimulatedActivity } from './lib/batchLoaders/simulatedActivityBatchLoader.js';
import type { CommandStem } from './lib/codegen/CommandEDSLPreface.js';
import getLogger from './utils/logger.js';
import { deserializeWithTemporal } from './utils/temporalSerializers.js';

const logger = getLogger(`[ Worker ${threadId} ]`);
logger.info('Starting worker thread...');

const temporalPolyfillTypes = fs.readFileSync(
  new URL('../src/types/TemporalPolyfillTypes.ts', import.meta.url).pathname,
  'utf8',
);

const channelDictionaryTypes: string = fs.readFileSync(
  new URL('./types/ChannelTypes.ts', import.meta.url).pathname,
  'utf-8',
);

const parameterDictionaryTypes: string = fs.readFileSync(
  new URL('./types/ParameterTypes.ts', import.meta.url).pathname,
  'utf-8',
);

const tsConfig = JSON.parse(fs.readFileSync(new URL('../tsconfig.json', import.meta.url).pathname, 'utf-8'));
const { options } = ts.parseJsonConfigFileContent(tsConfig, ts.sys, '');
const compilerTarget = options.target ?? ts.ScriptTarget.ES2021;

const codeRunner = new UserCodeRunner();

export async function typecheckExpansion(opts: {
  expansionLogic: string;
  commandTypes: string;
  activityTypes: string;
  activityTypeName?: string;
}): Promise<SerializedResult<CacheItem, ReturnType<UserCodeError['toJSON']>[]>> {
  const startTime = Date.now();
  logger.info(`started transpiling authoring logic - ${opts.activityTypeName || 'unknown'}`);

  const result = await codeRunner.preProcess(
    opts.expansionLogic,
    'ExpansionReturn',
    [
      '{ activityInstance: ActivityType, channelDictionary: ChannelDictionary, parameterDictionaries: ParameterDictionary[] }',
    ],
    [
      ts.createSourceFile('command-types.ts', opts.commandTypes, compilerTarget),
      ts.createSourceFile('activity-types.ts', opts.activityTypes, compilerTarget),
      ts.createSourceFile('TemporalPolyfillTypes.ts', temporalPolyfillTypes, compilerTarget),
      ts.createSourceFile('ChannelTypes.ts', channelDictionaryTypes, compilerTarget),
      ts.createSourceFile('ParameterDictionaryTypes.ts', parameterDictionaryTypes, compilerTarget),
    ],
  );

  const endTime = Date.now();
  logger.info(
    `finished transpiling ${opts.activityTypeName ? `- ${opts.activityTypeName}` : ''}, (${
      (endTime - startTime) / 1000
    } s)`,
  );

  if (result.isOk()) {
    return Result.Ok(result.unwrap()).toJSON();
  } else {
    return Result.Err(result.unwrapErr().map(err => err.toJSON())).toJSON();
  }
}

export async function executeEDSL(opts: {
  edslBody: string;
  commandTypes: string;
}): Promise<SerializedResult<SeqJson, ReturnType<UserCodeError['toJSON']>[]>> {
  const result = await codeRunner.executeUserCode(
    opts.edslBody,
    [],
    'Sequence',
    [],
    1000,
    [
      ts.createSourceFile('command-types.ts', opts.commandTypes, compilerTarget),
      ts.createSourceFile('TemporalPolyfillTypes.ts', temporalPolyfillTypes, compilerTarget),
    ],
    vm.createContext({
      Temporal,
    }),
  );

  if (result.isOk()) {
    let sequence = result.unwrap() as Sequence;
    return Result.Ok(sequence.toSeqJson()).toJSON();
  } else {
    return Result.Err(result.unwrapErr().map(err => err.toJSON())).toJSON();
  }
}

export async function executeExpansionFromBuildArtifacts(opts: {
  buildArtifacts: CacheItem;
  channelData?: ChannelDictionary;
  parameterData: ParameterDictionary[];
  serializedActivityInstance: SimulatedActivity;
}): Promise<SerializedResult<ReturnType<CommandStem['toSeqJson']>[], ReturnType<UserCodeError['toJSON']>[]>> {
  const activityInstance = deserializeWithTemporal(opts.serializedActivityInstance) as SimulatedActivity;
  const result = await codeRunner.executeUserCodeFromArtifacts<
    [
      {
        activityInstance: SimulatedActivity;
        channelDictionary: ChannelDictionary | undefined;
        parameterDictionaries: ParameterDictionary[];
      },
    ],
    ExpansionReturn
  >(
    opts.buildArtifacts.jsFileMap,
    opts.buildArtifacts.userCodeSourceMap,
    [
      {
        activityInstance,
        channelDictionary: opts.channelData,
        parameterDictionaries: opts.parameterData,
      },
    ],
    3000,
    vm.createContext({
      Temporal,
    }),
  );

  logger.info(`processing ${activityInstance.activityTypeName} at ${activityInstance.startTime.toLocaleString()}`);
  logger.info(`Memory RSS: ${formatMemoryNumber(process.memoryUsage().rss)}`);

  if (result.isOk()) {
    let commands = result.unwrap();
    if (!Array.isArray(commands)) {
      commands = [commands];
    }
    let commandsFlat = commands.flat() as CommandStem[];
    commandsFlat = commandsFlat.map(command => {
      return command.METADATA({
        ...command.GET_METADATA(),
        simulatedActivityId: activityInstance.id,
      });
    });

    return Result.Ok(commandsFlat.map(c => c.toSeqJson())).toJSON();
  } else {
    return Result.Err(result.unwrapErr().map(err => err.toJSON())).toJSON();
  }
}

function formatMemoryNumber(mem: number) {
  return `${Math.round((mem / 1024 / 1024) * 100) / 100} MB`;
}
