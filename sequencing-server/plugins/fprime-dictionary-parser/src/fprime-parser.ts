import * as ampcs from '@nasa-jpl/aerie-ampcs';
import { FormalParameter, FPPJSONDictionarySchema as FPPDictionary } from '../schema/fprime-types.js';
import { ChannelDictionary, CommandDictionary, ParameterDictionary } from '@nasa-jpl/aerie-ampcs';

export default {
  name: 'fprime-parser',
  version: '1.0.0',
  author: 'Ryan Goetz',
  parseDictionary(dictionaryString: string): {
    commandDictionary?: CommandDictionary,
    channelDictionary?: ChannelDictionary,
    parameterDictionary?: ParameterDictionary
  } {
    const dictionary = JSON.parse(dictionaryString) as FPPDictionary;

    const fswCommands = getCommands(dictionary);
    const fswCommandMap = createFswCommandMap(fswCommands);
    const enums = getEnums(dictionary);
    const enumMap = createEnumMap(enums);

    return {
      commandDictionary: {
        enumMap,
        enums,
        fswCommandMap,
        fswCommands,
        header: {
          mission_name: dictionary.metadata?.deploymentName ?? '',
          schema_version: dictionary.metadata?.dictionarySpecVersion ?? '',
          spacecraft_ids: [],
          version: dictionary.metadata?.projectVersion ?? '',
        },
        hwCommandMap: {},
        hwCommands: [],
        id: '',
        path: null,
      }
    }
  },
  processDictionary(parsedDictionary: ampcs.CommandDictionary) {
    return '';
  },
};

function getEnums(dictionary: FPPDictionary): ampcs.Enum[] {
  return (
    dictionary.typeDefinitions
      ?.filter(typeDefinition => typeDefinition.kind === 'enum')
      .map(
        (type: any) =>
          ({
            name: type.qualifiedName.replaceAll('.', '_'),
            values: type.enumeratedConstants.map((enumConstant: any) => ({
              numeric: enumConstant.value,
              symbol: enumConstant.name.replaceAll('.', '_'),
            })),
          } as ampcs.Enum),
      ) ?? []
  );
}

function createEnumMap(enums: ampcs.Enum[]): ampcs.EnumMap {
  return enums.reduce((acc, enumDef) => {
    acc[enumDef.name.replaceAll('.', '_')] = enumDef;
    return acc;
  }, {} as ampcs.EnumMap);
}

function getCommands(dictionary: FPPDictionary): ampcs.FswCommand[] {
  return dictionary.commands?.map(command => commandToAMPCSCommand(command, dictionary.typeDefinitions)) ?? [];
}

function createFswCommandMap(ampcsCommands: ampcs.FswCommand[]): ampcs.FswCommandMap {
  return ampcsCommands.reduce((acc, command) => {
    acc[command.stem] = command;
    return acc;
  }, {} as ampcs.FswCommandMap);
}

function commandToAMPCSCommand(
  fppCommand: {
    name: string;
    commandKind: string;
    opcode: number;
    annotation?: string;
    formalParams: FormalParameter[];
    priority?: number;
    queueFullBehavior?: 'assert' | 'block' | 'drop';
    [k: string]: unknown;
  },
  typeDefinitions: FPPDictionary['typeDefinitions'],
): ampcs.FswCommand {
  return {
    ...argsToAMPCSArgs(fppCommand.formalParams, typeDefinitions),
    description: fppCommand.annotation ?? '',
    stem: fppCommand.name.replaceAll('.', '_'),
    type: 'fsw_command',
  };
}

function argsToAMPCSArgs(
  args: FormalParameter[],
  typeDefinitions: FPPDictionary['typeDefinitions'],
): {
  arguments: ampcs.FswCommandArgument[];
  argumentMap: ampcs.FswCommandArgumentMap;
} {
  const ampcsArguments = args.map(arg => {
    return argToFSWArg(arg, typeDefinitions);
  });
  return {
    argumentMap: ampcsArguments.reduce((acc, arg) => {
      acc[arg.name.replaceAll('.', '_')] = arg;
      return acc;
    }, {} as ampcs.FswCommandArgumentMap),
    arguments: ampcsArguments,
  };
}

function argToFSWArg(
  arg: FormalParameter,
  typeDefinitions: FPPDictionary['typeDefinitions'],
): ampcs.FswCommandArgument {
  switch (arg.type.kind) {
    case 'integer':
      if (arg.type.signed === undefined) {
        throw new Error(`Since argument is an integer there needs to be a 'signed' property`);
      }
      if (arg.type.signed) {
        return {
          arg_type: 'integer',
          bit_length: arg.type.size ?? null,
          default_value: 0,
          description: arg.annotation ?? '',
          name: arg.name.replaceAll('.', '_'),
          range: null,
          units: '',
        } as ampcs.FswCommandArgumentInteger;
      } else {
        return {
          arg_type: 'unsigned',
          bit_length: arg.type.size ?? null,
          default_value: 0,
          description: arg.annotation ?? '',
          name: arg.name.replaceAll('.', '_'),
          range: null,
          units: '',
        } as ampcs.FswCommandArgumentUnsigned;
      }
    case 'float':
      return {
        arg_type: 'float',
        bit_length: arg.type.size ?? null,
        default_value: 0,
        description: arg.annotation ?? '',
        name: arg.name.replaceAll('.', '_'),
        range: null,
        units: '',
      } as ampcs.FswCommandArgumentFloat;
    case 'bool':
      return {
        arg_type: 'boolean',
        description: arg.annotation ?? '',
        name: arg.name.replaceAll('.', '_'),
        bit_length: arg.type.size ?? null,
        default_value: 'true',
        format: null,
      } as ampcs.FswCommandArgumentBoolean;
    case 'string':
      return {
        arg_type: 'var_string',
        description: arg.annotation ?? '',
        name: arg.name.replaceAll('.', '_'),
      } as ampcs.FswCommandArgumentVarString;
    case 'qualifiedIdentifier': {
      if (!typeDefinitions || !typeDefinitions.length) {
        throw new Error(`${arg.name} has no type defined in typeDefinitions`);
      }
      const typeDefinition = typeDefinitions.find(typeDefinition => typeDefinition.qualifiedName === arg.type.name);
      if (!typeDefinition) {
        throw new Error(`${arg.name} has no type defined in typeDefinitions`);
      }
      switch (typeDefinition.kind) {
        case 'enum':
          return {
            arg_type: 'enum',
            bit_length: arg.type.size ?? null,
            default_value: typeDefinition.default.split('.').pop() ?? null,
            description: arg.annotation ?? '',
            enum_name: typeDefinition.qualifiedName.replaceAll('.', '_'),
            name: arg.name.replaceAll('.', '_'),
            range: null,
          } as ampcs.FswCommandArgumentEnum;
        case 'array':
          return {
            arg_type: 'repeat',
            description: typeDefinition.annotation ?? '',
            name: typeDefinition.qualifiedName.replaceAll('.', '_'),
            prefix_bit_length: typeDefinition.elementType.size ?? null,
            repeat: {
              argumentMap: {},
              arguments: [
                argToFSWArg(
                  {
                    name: typeDefinition.elementType.name.replaceAll('.', '_'),
                    type: typeDefinition.elementType,
                    ref: false,
                    annotation: '',
                  } as FormalParameter,
                  typeDefinitions,
                ),
              ],
              min: 0,
              max: typeDefinition.size,
            },
          } as ampcs.FswCommandArgumentRepeat;
        case 'struct':
          return {
            arg_type: 'repeat',
            description: typeDefinition.annotation ?? '',
            name: typeDefinition.qualifiedName.replaceAll('.', '_'),
            prefix_bit_length: null,
            repeat: {
              argumentMap: {},
              arguments: Object.keys(typeDefinition.members).map(memberName => {
                return argToFSWArg(
                  {
                    name: memberName.replaceAll('.', '_'),
                    type: typeDefinition.members[memberName].type,
                    ref: false,
                    annotation: typeDefinition.members[memberName].annotation,
                  } as FormalParameter,
                  typeDefinitions,
                );
              }),
              min: 0,
              max: typeDefinition.size,
            },
          } as ampcs.FswCommandArgumentRepeat;
        default:
          throw new Error(`${arg.name} has no 'kind' defined by typeDefinitions`);
      }
    }
  }
}
