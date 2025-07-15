// Language: TypeScript
// Path: src/libs/CommandTypeCodegen.ts

import type * as ampcs from '@nasa-jpl/aerie-ampcs';
import fs from 'fs';
import reservedWords from 'reserved-words';
import type { ChannelDictionary, ParameterDictionary } from '@nasa-jpl/aerie-ampcs';
import { DictionaryType } from '../../types/types.js';
import { writeFile } from '../../utils/file.js';

const typescriptReservedWords = ['boolean', 'string', 'number'];

function generateTypescriptCode(dictionary: ampcs.CommandDictionary): {
  declarations: string;
  values: string;
} {
  const typescriptFswCommands: { value: string; interfaces: string }[] = [];
  for (const fswCommand of dictionary.fswCommands) {
    typescriptFswCommands.push(generateFswCommandCode(fswCommand, dictionary.enumMap));
  }

  const typescriptHwCommands: { value: string; interfaces: string }[] = [];
  for (const hwCommand of dictionary.hwCommands) {
    typescriptHwCommands.push(generateHwCommandCode(hwCommand));
  }

  // language=TypeScript
  const declarations = `
declare global {
${typescriptFswCommands.map(fswCommand => fswCommand.interfaces).join('\n')}${typescriptHwCommands
    .map(hwCommand => hwCommand.interfaces)
    .join('\n')}\n
\tconst Commands: {\n${dictionary.fswCommands
    .map(fswCommand => `\t\t${fswCommand.stem}: typeof ${fswCommand.stem}_STEP,\n`)
    .join('')}\t};

\tconst Hardwares : {\n${dictionary.hwCommands
    .map(hwCommand => `\t\t${hwCommand.stem}: typeof ${hwCommand.stem},\n`)
    .join('')} \t};
}`;

  // language=TypeScript
  const values = `
\nconst argumentOrders = {\n${dictionary.fswCommands
    .map(fswCommand => `\t'${fswCommand.stem}': [${generateArgOrder(fswCommand)}],\n`)
    .join('')}};

${typescriptFswCommands.map(fswCommand => fswCommand.value).join('\n')}
${typescriptHwCommands.map(hwCommands => hwCommands.value).join('\n')}\n
export const Commands = {\n${dictionary.fswCommands
    .map(fswCommand => `\t\t${fswCommand.stem}: ${fswCommand.stem}_STEP,\n`)
    .join('')}};

export const Immediates = {\n${dictionary.fswCommands
    .map(fswCommand => `\t\t${fswCommand.stem}: ${fswCommand.stem},\n`)
    .join('')}};

export const Hardwares = {\n${dictionary.hwCommands
    .map(hwCommands => `\t\t${hwCommands.stem}: ${hwCommands.stem},\n`)
    .join('')}};

Object.assign(globalThis, { A:A, R:R, E:E, C:Object.assign(Commands, STEPS, REQUESTS), Sequence, FLOAT, UINT,INT, STRING, ENUM, REQUEST, REF}, Hardwares, Immediates);
`;

  return {
    declarations,
    values,
  };
}

function generateFswCommandCode(
  fswCommand: ampcs.FswCommand,
  enumMap: ampcs.EnumMap,
): { value: string; interfaces: string } {
  const needsUnderscore =
    /^\d/.test(fswCommand.stem) ||
    reservedWords.check(fswCommand.stem) ||
    typescriptReservedWords.includes(fswCommand.stem);

  const fswCommandName = (needsUnderscore ? '_' : '') + fswCommand.stem;
  const numberOfArguments = fswCommand.arguments.length;

  const doc = generateDoc(fswCommand);

  if (numberOfArguments === 0) {
    // language=TypeScript
    const value = `
${doc}
const ${fswCommandName}: ${fswCommandName}_IMMEDIATE = ImmediateStem.new({
\tstem: '${fswCommand.stem}',
\targuments: [],
});
const ${fswCommandName}_STEP: ${fswCommandName}_STEP = CommandStem.new({
\tstem: '${fswCommand.stem}',
\targuments: [],
});`;

    const interfaces = `
${doc}
\tinterface ${fswCommandName}_IMMEDIATE extends ImmediateStem<[]> {}
\tinterface ${fswCommandName}_STEP extends CommandStem<[]> {}
\tconst ${fswCommandName}: ${fswCommandName}_IMMEDIATE;
`;
    return {
      value,
      interfaces,
    };
  }

  let argsWithType: Array<{ name: string; type: string }> = [];

  fswCommand.arguments.forEach(arg => {
    // If we come across a repeat arg we need to make an array type of all the repeats arguments.
    if (arg.arg_type === 'repeat' && arg.repeat) {
      let repeatArgs: Array<{ name: string; type: string }> = [];

      repeatArgs = repeatArgs.concat(
        arg.repeat.arguments.map(repeatArg => {
          return { name: `${repeatArg.name}`, type: mapArgumentType(repeatArg, enumMap) };
        }),
      );

      argsWithType = argsWithType.concat({
        name: `'${arg.name}'`,
        type: `Array<{ ${repeatArgs.map(repeatArg => `'${repeatArg.name}': ${repeatArg.type}`).join(', ')} }>`,
      });
    } else {
      // Otherwise we just have a normal arg with a type.
      argsWithType = argsWithType.concat({
        name: `'${arg.name}'`,
        type: mapArgumentType(arg, enumMap),
      });
    }
  });

  const value = `
${doc}
function ${fswCommandName}(...args:
\t\t| [ ${argsWithType.map(arg => convertObjectArgsToPassByPosition(arg.name, arg.type)).join(',')} ]
\t\t| [{ ${argsWithType.map(arg => arg.name + ': ' + arg.type).join(',')} }]) {
  return ImmediateStem.new({
    stem: '${fswCommandName}',
    arguments: typeof args[0] === 'object' && !Array.isArray(args[0]) && !(args[0] instanceof Variable) ? sortCommandArguments(args, argumentOrders['${fswCommandName}']) : commandArraysToObj(args, argumentOrders['${fswCommandName}']),
  }) as ${fswCommandName}_IMMEDIATE;
}
function ${fswCommandName}_STEP(...args:
\t\t|[ ${argsWithType
    .map(arg => `${convertObjectArgsToPassByPosition(arg.name, arg.type)} ${argumentTypeToVariable(arg.type)}`)
    .join(',')} ]
\t\t|[{ ${argsWithType.map(arg => arg.name + ': ' + arg.type + `${argumentTypeToVariable(arg.type)}`).join(',')} }]) {
  return CommandStem.new({
    stem: '${fswCommandName}',
    arguments: typeof args[0] === 'object' && !Array.isArray(args[0]) && !(args[0] instanceof Variable) ? sortCommandArguments(args, argumentOrders['${fswCommandName}']) : commandArraysToObj(args, argumentOrders['${fswCommandName}']),
  }) as ${fswCommandName}_STEP;
}`;

  const interfaces = `
\tinterface ${fswCommandName}_IMMEDIATE extends ImmediateStem<[
\t\t | [ ${argsWithType.map(arg => convertObjectArgsToPassByPosition(arg.name, arg.type)).join(',')}]
\t\t | [{ ${argsWithType.map(arg => arg.name + ': ' + arg.type).join(',')} }]]> {}
\tinterface ${fswCommandName}_STEP extends CommandStem<[
\t\t | [ ${argsWithType
    .map(arg => `${convertObjectArgsToPassByPosition(arg.name, arg.type)} ${argumentTypeToVariable(arg.type)}`)
    .join(',')}]
\t\t | [{ ${argsWithType
    .map(arg => arg.name + ': ' + arg.type + `${argumentTypeToVariable(arg.type)}`)
    .join(',')} }]]> {}
\tfunction ${fswCommandName}(...args:
\t\t| [ ${argsWithType.map(arg => convertObjectArgsToPassByPosition(arg.name, arg.type)).join(',')}]
\t\t| [{ ${argsWithType.map(arg => arg.name + ': ' + arg.type).join(',')} }]) : ${fswCommandName}_IMMEDIATE`;

  return {
    value,
    interfaces,
  };
}

/**
 * Converts object-based function arguments to pass-by-position format.
 *
 * This function takes a name and a type as input, where the type is represented
 * using TypeScript type notation. It converts the type to pass-by-position format
 * by replacing curly braces ('{}') with square brackets ('[]'), semicolons (';') with
 * commas (','), and removing single quotes from property names in the type.
 * The output will be a string representation of the name and the converted type in
 * pass-by-position format.
 *
 * @param {string} name - The name of the function argument.
 * @param {string} type - The TypeScript type representation of the function argument.
 * @returns {string} A string representation of the name and the converted type in
 * pass-by-position format.
 *
 * @example
 * const name = "'person'";
 * const type = "'TALL' | 'SHORT'";
 * const result = convertObjectArgsToPassByPosition(name, type);
 * // Output: "person : ['TALL' | 'SHORT']"
 *
 * @example
 * const name = "'Television'";
 * const type = "Array<{
 *   ops_cat: 'FULLSCREEN' | 'WINDOWED';
 *   channel_num: U16;
 * }>";
 * const result = convertObjectArgsToPassByPosition(name, type);
 * // Output: "Television : Array<[
 * //   ops_cat: 'FULLSCREEN' | 'WINDOWED',
 * //   channel_num: U16
 * // ]>"
 */
function convertObjectArgsToPassByPosition(name: string, type: string) {
  // remove ' around name
  name = name.replace(/'/g, '');
  // change [] to {} and ; to ,
  type = type.replace(/[{;}]/g, match => {
    switch (match) {
      case '{':
        return '[';
      case '}':
        return ']';
      case ';':
        return ',';
      default:
        return match;
    }
  });

  /*
   * The regex will remove single quotes only around property names (e.g., ops_cat) while
   * ignoring single quotes around specific literals (e.g., FULLSCREEN, WINDOWED).
   * For example:
   * Input:
   *     Array<[
   *       'ops_cat': 'FULLSCREEN' | 'WINDOWED',
   *       'channel_num': U16
   *     ]>
   *
   * Output:
   *     Array<[
   *       ops_cat: 'FULLSCREEN' | 'WINDOWED',
   *       channel_num: U16
   *     ]>
   */
  type = type.replace(/'([^']+)':/g, '$1:');

  return `${name} : ${type}`;
}

/**
 * Match the argument types in the command dictionary with the corresponding variable types
 * for both local and parameter in the seqjson specification.
 */
function argumentTypeToVariable(argumentType: string): string {
  if (argumentType.startsWith('F')) {
    return '| VARIABLE_FLOAT';
  } else if (argumentType.startsWith('I')) {
    return '| VARIABLE_INT';
  } else if (argumentType.startsWith('U')) {
    return '| VARIABLE_UINT';
  } else if (argumentType.startsWith('VarString')) {
    return '| VARIABLE_STRING';
  } else if (argumentType.startsWith('(')) {
    return '| VARIABLE_ENUM';
  } else {
    return '';
  }
}
function generateHwCommandCode(hwCommand: ampcs.HwCommand): { value: string; interfaces: string } {
  const needsUnderscore =
    /^\d/.test(hwCommand.stem) ||
    reservedWords.check(hwCommand.stem) ||
    typescriptReservedWords.includes(hwCommand.stem);

  const hwCommandName = (needsUnderscore ? '_' : '') + hwCommand.stem;

  const doc = generateDoc(hwCommand);
  const value =
    `${doc}` +
    `\nconst ${hwCommandName}: ${hwCommandName} = HardwareStem.new({` +
    `\n\tstem: '${hwCommand.stem}'` +
    `\n})`;

  const interfaces =
    `\t\t${doc}` + `\n\tinterface ${hwCommandName} extends HardwareStem {}\n\tconst ${hwCommandName}: ${hwCommandName}`;
  return {
    value,
    interfaces,
  };
}

function generateArgOrder(fswCommand: ampcs.FswCommand): string[] {
  let argOrder = [];

  for (const argument of fswCommand.arguments) {
    argOrder.push("'" + argument.name + "'");

    if (argument.arg_type === 'repeat' && argument.repeat?.arguments) {
      for (const repeatArg of argument.repeat?.arguments) {
        argOrder.push("'" + repeatArg.name + "'");
      }
    }
  }

  return argOrder;
}

/**
 * Creates a jsdoc style doc for the given command. Right now it just includes the args as
 * parameters.
 *
 * @param command The command we're generating documentation for.
 * @returns The generated documentation.
 */
function generateDoc(command: ampcs.FswCommand | ampcs.HwCommand): string {
  let parameters: string[] = [];

  if ('arguments' in command) {
    command.arguments.forEach(arg => {
      parameters.push(`* @param ${arg.name} ${arg.description}`);
    });
  }

  return `
/**
* ${command.description}
${parameters.length > 0 ? parameters.join('\n') : '*'}
*/`;
}

function mapArgumentType(argument: ampcs.FswCommandArgument, enumMap: ampcs.EnumMap): string {
  switch (argument.arg_type) {
    case 'enum':
      return `(${enumMap[argument.enum_name]?.values.map(value => `'${value.symbol}'`).join(' | ')})`;
    case 'boolean':
      return 'boolean';
    case 'float':
      return `F${argument.bit_length}`;
    case 'unsigned':
      return `U${argument.bit_length}`;
    case 'integer':
      return `I${argument.bit_length}`;
    case 'var_string':
      return `VarString<${argument.prefix_bit_length}, ${argument.max_bit_length}>`;
    case 'fixed_string':
      return 'FixedString';
    case 'time':
      return 'string';
    case 'repeat': {
      let repeatArgs: Array<{ name: string; type: string }> = [];
      if (argument.repeat === null) {
        return 'never';
      }
      repeatArgs = repeatArgs.concat(
        argument.repeat.arguments.map(repeatArg => {
          return { name: `${repeatArg.name}`, type: mapArgumentType(repeatArg, enumMap) };
        }),
      );
      return `Array<{ ${repeatArgs.map(repeatArg => `'${repeatArg.name}': ${repeatArg.type}`).join(', ')} }>`;
    }

    default:
      throw new Error(`Unsupported argument type: ${argument.arg_type}`);
  }
}

export async function processDictionary(
  dictionary: ampcs.CommandDictionary | ampcs.ChannelDictionary | ampcs.ParameterDictionary,
  type: 'COMMAND' | 'CHANNEL' | 'PARAMETER' = 'COMMAND',
): Promise<string> {
  const folderName = `${dictionary.header.mission_name.toLowerCase()}`;

  switch (type) {
    case DictionaryType.COMMAND: {
      const prefaceUrl = new URL('./CommandEDSLPreface.ts', import.meta.url);
      const jsonSpecUrl = new URL('../../../node_modules/@nasa-jpl/seq-json-schema/types.ts', import.meta.url);
      const fileName = `command_lib.${dictionary.header.version}.ts`;
      const prefaceString = await fs.promises.readFile(prefaceUrl.pathname, 'utf8');
      const jsonSpecString = `/** START Sequence JSON Spec */
  //https://github.com/NASA-AMMOS/seq-json-schema/blob/develop/types.ts\n
  ${await fs.promises.readFile(jsonSpecUrl.pathname, 'utf8')}
  \n/** END Sequence JSON Spec */`;

      const { values, declarations } = generateTypescriptCode(dictionary as ampcs.CommandDictionary); //update sql table store seprate

      return await writeFile(fileName, folderName, prefaceString + jsonSpecString + declarations + values);
    }
    case DictionaryType.CHANNEL: {
      const fileName = `channel_lib.${dictionary.header.version}.ts`;

      return await writeFile(fileName, folderName, JSON.stringify(dictionary as ChannelDictionary));
    }
    case DictionaryType.PARAMETER: {
      const fileName = `parameter_lib.${dictionary.header.version}.ts`;

      return await writeFile(fileName, folderName, JSON.stringify(dictionary as ParameterDictionary));
    }
    default:
      return 'Error processing command dictionary';
  }
}
