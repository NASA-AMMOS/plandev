import { Sequence } from '../../codegen/CommandEDSLPreface.js';
import type { SyntaxNode } from '@lezer/common';
import type {
  Activate,
  Args,
  BooleanArgument,
  Command,
  GroundBlock,
  GroundEpoch,
  GroundEvent,
  HardwareCommand,
  HexArgument,
  ImmediateFswCommand,
  Load,
  Metadata,
  Model,
  NumberArgument,
  RepeatArgument,
  Request,
  SeqJson,
  Step,
  StringArgument,
  SymbolArgument,
  Time,
  VariableDeclaration,
} from '@nasa-jpl/seq-json-schema/types';
import { TimeTypes } from './enums/time.js';
import { removeEscapedQuotes, unquoteUnescape } from './parse-utils.js';
import { getBalancedDuration, getDurationTimeComponents, parseDurationString, validateTime } from './time.js';
import { logInfo } from './logger.js';
import { SeqnLanguage } from './language/seqn.js';

const TOKEN_REPEAT_ARG = 'RepeatArg';

/**
 * Returns a minimal valid Seq JSON object.
 * Use for getting a default Seq JSON throughout the application.
 */
function seqJsonDefault(): SeqJson {
  return { id: '', metadata: {} };
}

/**
 * Walks the sequence parse tree and converts it to a valid Seq JSON object.
 */
export function seqnToSeqJson(text: string, sequenceName: string): Sequence {
  const node = SeqnLanguage.parser.parse(text);
  const baseNode = node.topNode;
  const seqJson: SeqJson = seqJsonDefault();
  const variableList: string[] = [];

  seqJson.id = parseId(baseNode, text, sequenceName);
  seqJson.metadata = { ...parseLGO(baseNode), ...parseMetadata(baseNode, text) };
  seqJson.locals = parseVariables(baseNode, text, 'LocalDeclaration') ?? undefined;
  if (seqJson.locals) {
    variableList.push(...seqJson.locals.map(value => value.name));
  }
  seqJson.parameters = parseVariables(baseNode, text, 'ParameterDeclaration') ?? undefined;
  if (seqJson.parameters) {
    variableList.push(...seqJson.parameters.map(value => value.name));
  }
  let child = baseNode.getChild('Commands')?.firstChild;
  seqJson.steps = [];
  while (child) {
    const step = parseStep(child, text);
    if (step) {
      seqJson.steps.push(step);
    }
    child = child?.nextSibling;
  }
  if (!seqJson.steps.length) {
    seqJson.steps = undefined;
  }
  seqJson.immediate_commands =
    baseNode
      .getChild('ImmediateCommands')
      ?.getChildren('Command')
      .map(command => parseImmediateCommand(command, text)) ?? undefined;
  seqJson.hardware_commands =
    baseNode
      .getChild('HardwareCommands')
      ?.getChildren('Command')
      .map(command => parseHardwareCommand(command, text)) ?? undefined;

  seqJson.requests = baseNode
    .getChild('Commands')
    ?.getChildren('Request')
    .map(requestNode => parseRequest(requestNode, text));
  if (seqJson.requests?.length === 0) {
    seqJson.requests = undefined;
  }

  return Sequence.fromSeqJson(seqJson);
}

function parseRequest(requestNode: SyntaxNode, text: string): Request {
  let ground_epoch = undefined;
  let time = undefined;
  const groundEpochNode = requestNode.getChild('TimeTag')?.getChild('TimeGroundEpoch');
  if (groundEpochNode) {
    ground_epoch = parseGroundEpoch(requestNode.getChild('TimeTag'), text);
  } else {
    time = parseTime(requestNode, text);
  }
  const nameNode = requestNode.getChild('RequestName');
  const name = nameNode ? unquoteUnescape(text.slice(nameNode.from, nameNode.to)) : 'UNKNOWN';
  const description = parseDescription(requestNode, text);
  const metadata = parseMetadata(requestNode, text);

  const steps: Step[] = [];
  const stepsNode = requestNode.getChild('Steps');
  if (stepsNode) {
    let stepNode = stepsNode.firstChild;
    while (stepNode) {
      const step = parseStep(stepNode, text);
      if (step) {
        steps.push(step);
      }
      stepNode = stepNode?.nextSibling;
    }
  }

  if (steps.length === 0) {
    // request with empty steps is disallowed in seq.json
    steps.push({
      args: [],
      stem: 'UNKNOWN',
      time: { tag: 'UNKNOWN', type: 'ABSOLUTE' },
      type: 'command',
    });
  }

  // ground epoch
  return {
    description,
    ground_epoch,
    metadata,
    name,
    steps: steps as [Step, ...Step[]],
    time,
    type: 'request',
  };
}

function parseGroundBlockEvent(stepNode: SyntaxNode, text: string): GroundBlock | GroundEvent {
  const time = parseTime(stepNode, text);

  const nameNode = stepNode.getChild('GroundName');
  const name = nameNode ? unquoteUnescape(text.slice(nameNode.from, nameNode.to)) : 'UNKNOWN';

  const argsNode = stepNode.getChild('Args');
  const args = argsNode ? parseArgs(argsNode, text) : [];

  const description = parseDescription(stepNode, text);
  const metadata = parseMetadata(stepNode, text);
  const models = parseModel(stepNode, text);

  return {
    args,
    description,
    metadata,
    models,
    name,
    time,
    type: stepNode.name === 'GroundBlock' ? 'ground_block' : 'ground_event',
  };
}

function parseActivateLoad(stepNode: SyntaxNode, text: string): Activate | Load {
  const time = parseTime(stepNode, text);

  const nameNode = stepNode.getChild('SequenceName');
  const sequence = nameNode ? unquoteUnescape(text.slice(nameNode.from, nameNode.to)) : 'UNKNOWN';

  const argsNode = stepNode.getChild('Args');
  const args = argsNode ? parseArgs(argsNode, text) : [];

  const engine = parseEngine(stepNode, text);
  const epoch = parseEpoch(stepNode, text);

  const description = parseDescription(stepNode, text);
  const metadata = parseMetadata(stepNode, text);
  const models = parseModel(stepNode, text);

  return {
    args,
    description,
    engine,
    epoch,
    metadata,
    models,
    sequence,
    time,
    type: stepNode.name === 'Load' ? 'load' : 'activate',
  };
}

function parseEngine(stepNode: SyntaxNode, text: string): number | undefined {
  const engineNode = stepNode.getChild('Engine')?.getChild('Number');
  return engineNode ? parseInt(text.slice(engineNode.from, engineNode.to), 10) : undefined;
}

function parseEpoch(stepNode: SyntaxNode, text: string): string | undefined {
  const epochNode = stepNode.getChild('Epoch')?.getChild('String');
  return epochNode ? unquoteUnescape(text.slice(epochNode.from, epochNode.to)) : undefined;
}

function parseStep(child: SyntaxNode, text: string): Step | null {
  switch (child.name) {
    case 'Command':
      return parseCommand(child, text);
    case 'Activate':
    case 'Load':
      return parseActivateLoad(child, text);
    case 'GroundBlock':
    case 'GroundEvent':
      return parseGroundBlockEvent(child, text);
  }
  return null;
}

function parseLGO(node: SyntaxNode): Metadata | undefined {
  const lgoNode = node.getChild('Commands')?.getChild('LoadAndGoDirective');
  if (!lgoNode) {
    return undefined;
  }

  return {
    lgo: true,
  };
}

function parseArg(
  node: SyntaxNode,
  text: string,
): BooleanArgument | HexArgument | NumberArgument | StringArgument | SymbolArgument | undefined {
  const nodeValue = text.slice(node.from, node.to);

  if (node.name === 'Boolean') {
    const value = nodeValue === 'true' ? true : false;
    const booleanArg: BooleanArgument = { type: 'boolean', value };
    return booleanArg;
  } else if (node.name === 'Enum') {
    const value = nodeValue;
    const enumArg: SymbolArgument = { type: 'symbol', value };
    return enumArg;
  } else if (node.name === 'Number') {
    if (nodeValue.slice(0, 2) === '0x') {
      const hexArg: HexArgument = { type: 'hex', value: nodeValue };
      return hexArg;
    } else {
      const value = parseFloat(nodeValue);
      const numberArg: NumberArgument = { type: 'number', value };
      return numberArg;
    }
  } else if (node.name === 'String') {
    const value = JSON.parse(nodeValue);
    const arg: StringArgument = { type: 'string', value };
    return arg;
  }
  logInfo(`Could not parse arg value ${nodeValue} for node ${node.name}`);
  return;
}

function parseRepeatArgs(repeatArgsNode: SyntaxNode, text: string): RepeatArgument {
  let args: RepeatArgument['value'][0] = [];
  let argNode = repeatArgsNode.firstChild;
  while (argNode) {
    const arg = parseArg(argNode, text);
    if (arg) {
      args.push(arg);
    }
    argNode = argNode.nextSibling;
  }

  return { type: 'repeat', value: [args] };
}

function parseArgs(argsNode: SyntaxNode, text: string): Args {
  const args: Args = [];
  let argNode = argsNode.firstChild;

  while (argNode) {
    if (argNode.name === TOKEN_REPEAT_ARG) {
      const arg = parseRepeatArgs(argNode, text);
      if (arg) {
        args.push(arg);
      } else {
        logInfo(`Could not parse repeat arg for node with name ${argNode.name}`);
      }
    } else {
      const arg = parseArg(argNode, text);
      if (arg) {
        args.push(arg);
      } else {
        logInfo(`Could not parse arg for node with name ${argNode.name}`);
      }
    }
    argNode = argNode?.nextSibling;
  }

  return args;
}

function parseGroundEpoch(groundEpochNode: SyntaxNode | null, text: string): GroundEpoch {
  if (!groundEpochNode) {
    return { delta: '', name: '' };
  }

  const nameNode = groundEpochNode.getChild('Name');
  let tag = '';

  if (groundEpochNode.parent) {
    const time = parseTime(groundEpochNode.parent, text);

    if (time.type !== 'COMMAND_COMPLETE') {
      tag = time.tag;
    }
  }
  return {
    delta: tag,
    name: nameNode ? unquoteUnescape(text.slice(nameNode.from, nameNode.to)) : '',
  };
}

/**
 * Parses a time tag node and returns a Seq JSON time.
 * Defaults to an unknown absolute time if a command does not have a valid time tag.
 */
function parseTime(commandNode: SyntaxNode, text: string): Time {
  const timeTagNode = commandNode.getChild('TimeTag');
  let tag = 'UNKNOWN';

  if (timeTagNode == null) {
    return { tag, type: 'ABSOLUTE' };
  }

  const timeTagAbsoluteNode = timeTagNode.getChild('TimeAbsolute');
  const timeTagCompleteNode = timeTagNode.getChild('TimeComplete');
  const timeTagEpochNode = timeTagNode.getChild('TimeEpoch') || timeTagNode.getChild('TimeGroundEpoch');
  const timeTagRelativeNode = timeTagNode.getChild('TimeRelative');

  if (timeTagCompleteNode) {
    return { type: 'COMMAND_COMPLETE' };
  }

  if (!timeTagAbsoluteNode && !timeTagEpochNode && !timeTagRelativeNode) {
    return { tag, type: 'ABSOLUTE' };
  }

  if (timeTagAbsoluteNode) {
    const tag = text.slice(timeTagAbsoluteNode.from + 1, timeTagAbsoluteNode.to).trim();
    return { tag, type: 'ABSOLUTE' };
  } else if (timeTagEpochNode) {
    const timeTagEpochText = text.slice(timeTagEpochNode.from + 1, timeTagEpochNode.to).trim();

    // a regex to determine if this string [+/-]####T##:##:##.###
    if (validateTime(timeTagEpochText, TimeTypes.EPOCH)) {
      const { isNegative, days, hours, minutes, seconds, milliseconds } = getDurationTimeComponents(
        parseDurationString(timeTagEpochText, 'seconds'),
      );
      tag = `${isNegative}${days}${days ? 'T' : ''}${hours}:${minutes}:${seconds}${
        milliseconds ? '.' : ''
      }${milliseconds}`;
      return { tag, type: 'EPOCH_RELATIVE' };
    }

    // a regex to determine if this string [+/-]###.###
    if (validateTime(timeTagEpochText, TimeTypes.EPOCH_SIMPLE)) {
      tag = getBalancedDuration(timeTagEpochText);
      if (parseDurationString(tag, 'seconds').milliseconds === 0) {
        tag = tag.slice(0, -4);
      }
      return { tag, type: 'EPOCH_RELATIVE' };
    }
  } else if (timeTagRelativeNode) {
    const timeTagRelativeText = text.slice(timeTagRelativeNode.from + 1, timeTagRelativeNode.to).trim();

    // a regex to determine if this string ####T##:##:##.###
    if (validateTime(timeTagRelativeText, TimeTypes.RELATIVE)) {
      const { isNegative, days, hours, minutes, seconds, milliseconds } = getDurationTimeComponents(
        parseDurationString(timeTagRelativeText, 'seconds'),
      );
      tag = `${isNegative}${days}${days ? 'T' : ''}${hours}:${minutes}:${seconds}${
        milliseconds ? '.' : ''
      }${milliseconds}`;
      return { tag, type: 'COMMAND_RELATIVE' };
    }

    if (validateTime(timeTagRelativeText, TimeTypes.RELATIVE_SIMPLE)) {
      tag = getBalancedDuration(timeTagRelativeText);
      if (parseDurationString(tag).milliseconds === 0) {
        tag = tag.slice(0, -4);
      }
      return { tag, type: 'COMMAND_RELATIVE' };
    }
  }
  return { tag, type: 'ABSOLUTE' };
}

// min length of one
type VariableDeclarationArray = [VariableDeclaration, ...VariableDeclaration[]];

export function parseVariables(
  node: SyntaxNode,
  text: string,
  type: 'LocalDeclaration' | 'ParameterDeclaration' = 'LocalDeclaration',
): VariableDeclarationArray | undefined {
  const variableContainer = node.getChild(type);
  if (!variableContainer) {
    return undefined;
  }
  const variables = variableContainer.getChildren('Variable');
  if (!variables || variables.length === 0) {
    return undefined;
  }

  return variables.map((variableNode: SyntaxNode) => {
    const nameNode = variableNode.getChild('Enum');
    const typeNode = variableNode.getChild('Type');
    const enumNode = variableNode.getChild('EnumName');
    const rangeNode = variableNode.getChild('Range');
    const allowableValuesNode = variableNode.getChild('Values');
    const objects = variableNode.getChildren('Object');

    const variableText = nameNode ? text.slice(nameNode.from, nameNode.to) : 'UNKNOWN';
    const variable: VariableDeclaration = { name: variableText, type: 'INT' };

    if (typeNode) {
      variable.type = text.slice(typeNode.from, typeNode.to) as 'FLOAT' | 'INT' | 'STRING' | 'UINT' | 'ENUM';
      if (enumNode) {
        variable.enum_name = text.slice(enumNode.from, enumNode.to);
      }
      if (rangeNode) {
        const allowableRanges = parseAllowableRanges(text, rangeNode);
        if (allowableRanges && allowableRanges.length > 0) {
          variable.allowable_ranges = allowableRanges;
        }
      }
      if (allowableValuesNode) {
        const allowableValues = parseAllowableValues(text, allowableValuesNode);
        if (allowableValues && allowableValues.length > 0) {
          variable.allowable_values = allowableValues;
        }
      }
    } else {
      for (const object of objects) {
        const properties = object.getChildren('Property');

        properties.forEach(property => {
          const propertyName = property.getChild('PropertyName');
          const propertyValue = propertyName?.nextSibling;
          const propertyNameString = text.slice(propertyName?.from, propertyName?.to).replaceAll('"', '');
          const propertyValueString = text.slice(propertyValue?.from, propertyValue?.to).replaceAll('"', '');

          switch (propertyNameString.toLowerCase()) {
            case 'allowable_ranges': {
              if (!propertyValue) {
                break;
              }
              const allowableRanges = parseAllowableRanges(text, propertyValue);
              if (allowableRanges && allowableRanges.length > 0) {
                variable.allowable_ranges = allowableRanges;
              }
              break;
            }
            case 'allowable_values':
              {
                if (!propertyValue) {
                  break;
                }
                const allowableValues = parseAllowableValues(text, propertyValue);
                if (allowableValues && allowableValues.length > 0) {
                  variable.allowable_values = allowableValues;
                }
              }
              break;
            case 'enum_name':
              variable.enum_name = propertyValueString;
              break;
            case 'sc_name':
              variable.sc_name = propertyValueString;
              break;
            case 'type':
              variable.type = propertyValueString as 'FLOAT' | 'INT' | 'STRING' | 'UINT' | 'ENUM';
              break;
          }
        });
      }
    }

    const match = /(?:[a-zA-Z]*)(?:[0-9]{2})(INT|UINT|FLT|ENUM|STR)/g.exec(variableText);
    if (match) {
      const kind = match[1];

      switch (kind) {
        case 'STR':
          variable.type = 'STRING';
          break;
        case 'FLT':
          variable.type = 'FLOAT';
          break;
        case 'INT':
          variable.type = 'INT';
          break;
        case 'UINT':
          variable.type = 'UINT';
          break;
        case 'ENUM':
          variable.type = 'ENUM';
          break;
      }

      return variable;
    } else {
      return variable;
    }
  }) as VariableDeclarationArray;
}

function parseAllowableRanges(text: string, rangeNode: any): { max: number; min: number }[] {
  return text
    .slice(rangeNode.from, rangeNode.to)
    .split(',')
    .map(range => {
      const rangeMatch = /^([-+]?\d+)?(\.\.\.)([-+]?\d+)?$/.exec(range.replaceAll('"', '').trim());
      if (rangeMatch) {
        const [, min, , max] = rangeMatch;
        const parsedMaxNum = Number(max);
        const parsedMinNum = Number(min);
        const maxNum = !Number.isNaN(parsedMaxNum) ? parsedMaxNum : Infinity;
        const minNum = !Number.isNaN(parsedMinNum) ? parsedMinNum : -Infinity;

        return { max: maxNum, min: minNum };
      }
      return undefined;
    })
    .filter(range => range !== undefined) as { max: number; min: number }[];
}

function parseAllowableValues(text: string, allowableValuesNode: any): string[] | undefined {
  const allowableValues = text
    .slice(allowableValuesNode.from + 1, allowableValuesNode.to - 1)
    .split(',')
    .map(value => value.trim());

  return allowableValues.length > 0 ? allowableValues : undefined;
}

function parseModel(node: SyntaxNode, text: string): Model[] | undefined {
  const modelContainer = node.getChild('Models');
  if (!modelContainer) {
    return undefined;
  }

  const modelNodes = modelContainer.getChildren('Model');
  if (!modelNodes || modelNodes.length === 0) {
    return undefined;
  }

  const models: Model[] = [];
  for (const modelNode of modelNodes) {
    const variableNode = modelNode.getChild('Variable');
    const valueNode = modelNode.getChild('Value');
    const offsetNode = modelNode.getChild('Offset');

    const variable = variableNode ? unquoteUnescape(text.slice(variableNode.from, variableNode.to)) : 'UNKNOWN';

    // Value can be string, number or boolean
    let value: Model['value'] = 0;
    const valueChild = valueNode?.firstChild;
    if (valueChild) {
      const valueText = text.slice(valueChild.from, valueChild.to);
      if (valueChild.name === 'String') {
        value = unquoteUnescape(valueText);
      } else if (valueChild.name === 'Boolean') {
        value = !/^FALSE$/i.test(valueText);
      } else if (valueChild.name === 'Number') {
        value = Number(valueText);
      }
    }
    const offset = offsetNode ? unquoteUnescape(text.slice(offsetNode.from, offsetNode.to)) : 'UNKNOWN';

    models.push({ offset, value, variable });
  }

  return models;
}

function parseDescription(node: SyntaxNode, text: string): string | undefined {
  const descriptionNode = node.getChild('LineComment');
  if (!descriptionNode) {
    return undefined;
  }
  // +1 offset to drop '#' prefix
  const description = text.slice(descriptionNode.from + 1, descriptionNode.to).trim();
  return removeEscapedQuotes(description);
}

function parseCommand(commandNode: SyntaxNode, text: string): Command {
  const time = parseTime(commandNode, text);

  const stemNode = commandNode.getChild('Stem');
  const stem = stemNode ? text.slice(stemNode.from, stemNode.to) : 'UNKNOWN';

  const argsNode = commandNode.getChild('Args');
  const args = argsNode ? parseArgs(argsNode, text) : [];

  const description = parseDescription(commandNode, text);
  const metadata: Metadata | undefined = parseMetadata(commandNode, text);
  const models: Model[] | undefined = parseModel(commandNode, text);

  return {
    args,
    description,
    metadata,
    models,
    stem,
    time,
    type: 'command',
  };
}

function parseImmediateCommand(commandNode: SyntaxNode, text: string): ImmediateFswCommand {
  const stemNode = commandNode.getChild('Stem');
  const stem = stemNode ? text.slice(stemNode.from, stemNode.to) : 'UNKNOWN';

  const argsNode = commandNode.getChild('Args');
  const args = argsNode ? parseArgs(argsNode, text) : [];

  const description = parseDescription(commandNode, text);
  const metadata: Metadata | undefined = parseMetadata(commandNode, text);

  return {
    args,
    description,
    metadata,
    stem,
  };
}

function parseHardwareCommand(commandNode: SyntaxNode, text: string): HardwareCommand {
  const stemNode = commandNode.getChild('Stem');
  const stem = stemNode ? text.slice(stemNode.from, stemNode.to) : 'UNKNOWN';
  const description = parseDescription(commandNode, text);
  const metadata: Metadata | undefined = parseMetadata(commandNode, text);

  return {
    description,
    metadata,
    stem,
  };
}

/**
 *  This looks for a @ID directive to specify sequence id, if not present use ground name without extensions
 *
 * @param node - top node of parsed tree
 * @param text - text of sequence
 * @param sequenceName - ground name of sequence
 * @returns
 */
function parseId(node: SyntaxNode, text: string, sequenceName: string): string {
  const stringNode = node.getChild('IdDeclaration')?.getChild('String');
  if (!stringNode) {
    return sequenceName.split('.')[0];
  }

  const id = JSON.parse(text.slice(stringNode.from, stringNode.to));
  return id;
}

function parseMetadata(node: SyntaxNode, text: string): Metadata | undefined {
  const metadataNode = node.getChild('Metadata');
  if (!metadataNode) {
    return undefined;
  }

  const metadataEntry = metadataNode.getChildren('MetaEntry');
  if (!metadataEntry || metadataEntry.length === 0) {
    return undefined;
  }

  const obj: Metadata = {};
  metadataEntry.forEach(entry => {
    const keyNode = entry.getChild('Key');
    const valueNode = entry.getChild('Value');

    if (!keyNode || !valueNode) {
      return; // Skip this entry if either the key or value is missing
    }

    const keyText = unquoteUnescape(text.slice(keyNode.from, keyNode.to));

    let value = text.slice(valueNode.from, valueNode.to);
    try {
      value = JSON.parse(value);
    } catch (e) {
      logInfo(`Malformed metadata ${value}`);
    }

    obj[keyText] = value;
  });

  return obj;
}
