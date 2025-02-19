import { syntaxTree } from '@codemirror/language';
import { linter, type Diagnostic } from '@codemirror/lint';
import type { Extension } from '@codemirror/state';
import {
  type ChannelDictionary,
  type CommandDictionary,
  type FswCommandArgument,
  type ParameterDictionary,
} from '@nasa-jpl/aerie-ampcs';
import { get } from 'svelte/store';
import { inputFormat, sequenceAdaptation } from '../../stores/sequence-adaptation';
import type { IOutputFormat, LibrarySequence } from '../../types/sequencing';
import { seqJsonLinter } from './seq-json-linter';
import { sequenceLinter } from './sequence-linter';

// TODO: serialization
// replace parameter names with hex ids
// replace enum parameter values with the numeric value

//

export type ArgDelegator = {
  [stem: string]: {
    [arg: string]:
      | undefined
      | ((
          argDef: FswCommandArgument,
          paramDictionaries: ParameterDictionary[],
          channelDictionary: ChannelDictionary | null,
          precedingArgValues: string[],
        ) => FswCommandArgument | undefined);
  };
};

export function getCustomArgDef(
  stem: string,
  dictArg: FswCommandArgument,
  precedingArgs: string[],
  parameterDictionaries: ParameterDictionary[],
  channelDictionary: ChannelDictionary | null,
) {
  let delegate = undefined;

  if (get(sequenceAdaptation).argDelegator !== undefined) {
    delegate = get(sequenceAdaptation).argDelegator?.[stem]?.[dictArg.name];
  }

  return delegate?.(dictArg, parameterDictionaries, channelDictionary, precedingArgs) ?? dictArg;
}

export async function toInputFormat(
  output: string,
  parameterDictionaries: ParameterDictionary[],
  channelDictionary: ChannelDictionary | null,
) {
  const modifyOutputParse = get(sequenceAdaptation).modifyOutputParse;
  if (modifyOutputParse !== undefined) {
    let modifiedOutput = await modifyOutputParse(output, parameterDictionaries, channelDictionary);
    if (modifiedOutput === null) {
      modifiedOutput = 'modifyOutputParse returned null. Verify your adaptation is correct';
    } else if (modifiedOutput === undefined) {
      modifiedOutput = 'modifyOutputParse returned undefined. Verify your adaptation is correct';
    } else if (typeof modifiedOutput === 'object') {
      modifiedOutput = JSON.stringify(modifiedOutput);
    } else {
      modifiedOutput = `${modifiedOutput}`;
    }

    return (await get(inputFormat)?.toInputFormat?.(modifiedOutput)) ?? output;
  } else {
    try {
      return (await get(inputFormat)?.toInputFormat?.(output)) ?? output;
    } catch (e) {
      console.error(e);
      return output;
    }
  }
}

export function inputLinter(
  channelDictionary: ChannelDictionary | null = null,
  commandDictionary: CommandDictionary | null = null,
  parameterDictionaries: ParameterDictionary[] = [],
  librarySequences: LibrarySequence[] = [],
): Extension {
  return linter(view => {
    const inputLinter = get(sequenceAdaptation).inputFormat.linter;
    const tree = syntaxTree(view.state);
    const treeNode = tree.topNode;
    let diagnostics: Diagnostic[];

    diagnostics = sequenceLinter(view, channelDictionary, commandDictionary, parameterDictionaries, librarySequences);

    if (inputLinter !== undefined && commandDictionary !== null) {
      diagnostics = inputLinter(diagnostics, commandDictionary, view, treeNode);
    }

    return diagnostics;
  });
}

export function outputLinter(
  commandDictionary: CommandDictionary | null = null,
  outputFormat: IOutputFormat | undefined = undefined,
): Extension {
  return linter(view => {
    const tree = syntaxTree(view.state);
    const treeNode = tree.topNode;
    const outputLinter = outputFormat?.linter;
    let diagnostics: Diagnostic[];

    diagnostics = seqJsonLinter(view, commandDictionary);

    if (outputLinter !== undefined && commandDictionary !== null) {
      diagnostics = outputLinter(diagnostics, commandDictionary, view, treeNode);
    }

    return diagnostics;
  });
}
