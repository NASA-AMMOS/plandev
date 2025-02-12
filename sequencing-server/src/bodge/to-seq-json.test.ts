import {
  parse,
  type CommandDictionary,
  type FswCommand,
  type FswCommandArgument,
  type FswCommandArgumentFloat,
  type FswCommandArgumentMap,
  type HwCommand,
} from '@nasa-jpl/aerie-ampcs';
import type { VariableDeclaration } from '@nasa-jpl/seq-json-schema/types';
import { readFileSync } from 'fs';
import { describe, expect, it } from 'vitest';
import { SeqLanguage } from '../codemirror';
import { parser } from '../codemirror/sequence.grammar';
import { seqJsonToSequence } from './from-seq-json';
import { parseVariables, sequenceToSeqJson } from './to-seq-json';

function argArrToMap(cmdArgs: FswCommandArgument[]): FswCommandArgumentMap {
  return cmdArgs.reduce((argMap, arg) => ({ ...argMap, [arg.name]: arg }), {});
}

function floatArg(name: string, units: string = ''): FswCommandArgumentFloat {
  return {
    arg_type: 'float',
    bit_length: 64,
    default_value: 0,
    description: '',
    name,
    range: { max: 5, min: -5 },
    units,
  };
}

const fswCommands: FswCommand[] = [
  {
    arguments: [floatArg('float_arg_1', ''), floatArg('float_arg_2', '')] as FswCommandArgument[],
    stem: 'FSW_CMD_1',
  },
].map(stub => ({ ...stub, argumentMap: argArrToMap(stub.arguments), description: '', type: 'fsw_command' }));

const fswCommandMap = fswCommands.reduce((cmdMap, hwCmd) => ({ ...cmdMap, [hwCmd.stem]: hwCmd }), {});

const hwCommands = [
  {
    stem: 'HDW_CMD_1',
  },
  {
    stem: 'HDW_CMD_2',
  },
].map((stub): HwCommand => ({ ...stub, description: '', type: 'hw_command' }));

const hwCommandMap = hwCommands.reduce((cmdMap, hwCmd) => ({ ...cmdMap, [hwCmd.stem]: hwCmd }), {});

const commandDictionary: CommandDictionary = {
  enumMap: {},
  enums: [],
  fswCommandMap,
  fswCommands,
  header: {
    mission_name: 'unittest',
    schema_version: '1',
    spacecraft_ids: [1],
    version: '1',
  },
  hwCommandMap,
  hwCommands,
  id: 'command_dictionary',
  path: '/file/path',
};

const commandBanana = parse(readFileSync('src/tests/mocks/sequencing/dictionaries/command_banananation.xml', 'utf-8'));

describe('convert a sequence to seq json', async () => {
  it('hardware command', async () => {
    const seq = `@HARDWARE
HDW_CMD`;
    const id = 'test';
    const expectedJson = {
      hardware_commands: [
        {
          stem: 'HDW_CMD',
        },
      ],
      id: 'test',
      metadata: {},
    };
    const actual = JSON.parse(await sequenceToSeqJson(SeqLanguage.parser.parse(seq), seq, commandDictionary, id));
    expect(actual).toEqual(expectedJson);
  });

  it('multiple hardware commands', async () => {
    const seq = `@HARDWARE
HDW_CMD_1
# comment
HDW_CMD_2
`;
    const id = 'test';
    const expectedJson = {
      hardware_commands: [
        {
          stem: 'HDW_CMD_1',
        },
        {
          stem: 'HDW_CMD_2',
        },
      ],
      id: 'test',
      metadata: {},
    };
    const actual = JSON.parse(await sequenceToSeqJson(SeqLanguage.parser.parse(seq), seq, commandDictionary, id));
    expect(actual).toEqual(expectedJson);
  });

  it('load and go with commands', async () => {
    const seq = `@LOAD_AND_GO
C FSW_CMD_1 1e3 2.34
# comment
C FSW_CMD_1 0.123 -2.34 # inline description
`;
    const id = 'test';
    const expectedJson = {
      id: 'test',
      metadata: {
        lgo: true,
      },
      steps: [
        {
          args: [
            {
              name: 'float_arg_1',
              type: 'number',
              value: 1000,
            },
            {
              name: 'float_arg_2',
              type: 'number',
              value: 2.34,
            },
          ],
          stem: 'FSW_CMD_1',
          time: {
            type: 'COMMAND_COMPLETE',
          },
          type: 'command',
        },
        {
          args: [
            {
              name: 'float_arg_1',
              type: 'number',
              value: 0.123,
            },
            {
              name: 'float_arg_2',
              type: 'number',
              value: -2.34,
            },
          ],
          description: 'inline description',
          stem: 'FSW_CMD_1',
          time: {
            type: 'COMMAND_COMPLETE',
          },
          type: 'command',
        },
      ],
    };
    const actual = JSON.parse(await sequenceToSeqJson(SeqLanguage.parser.parse(seq), seq, commandDictionary, id));
    expect(actual).toEqual(expectedJson);
  });

  it('command dictionary file', async () => {
    const id = 'test.sequence';
    const seq = `
@ID "test.inline"

@INPUT_PARAMS L00INT L01INT L02STR

# comment
R10 ECHO    "string arg"
R71 ECHO    L02STR
    `;
    const expectedJson = {
      id: 'test.inline',
      metadata: {},
      parameters: [
        {
          name: 'L00INT',
          type: 'INT',
        },
        {
          name: 'L01INT',
          type: 'INT',
        },
        {
          name: 'L02STR',
          type: 'STRING',
        },
      ],
      steps: [
        {
          args: [
            {
              name: 'echo_string',
              type: 'string',
              value: 'string arg',
            },
          ],
          stem: 'ECHO',
          time: {
            tag: '00:00:10',
            type: 'COMMAND_RELATIVE',
          },
          type: 'command',
        },
        {
          args: [
            {
              name: 'echo_string',
              type: 'symbol',
              value: 'L02STR',
            },
          ],
          stem: 'ECHO',
          time: {
            tag: '00:01:11',
            type: 'COMMAND_RELATIVE',
          },
          type: 'command',
        },
      ],
    };
    const actual = JSON.parse(await sequenceToSeqJson(SeqLanguage.parser.parse(seq), seq, commandBanana, id));
    expect(actual).toEqual(expectedJson);
  });

  it('repeat args', async () => {
    const id = 'test.sequence';
    const seq = `@ID "test.inline"

# comment
R10 PACKAGE_BANANA     2      [    "bundle1"    5 "bundle2" 10]
    `;
    const expectedJson = {
      id: 'test.inline',
      metadata: {},
      steps: [
        {
          args: [
            {
              name: 'lot_number',
              type: 'number',
              value: 2,
            },
            {
              name: 'bundle',
              type: 'repeat',
              value: [
                [
                  {
                    name: 'bundle_name',
                    type: 'string',
                    value: 'bundle1',
                  },
                  {
                    name: 'number_of_bananas',
                    type: 'number',
                    value: 5,
                  },
                ],
                [
                  {
                    name: 'bundle_name',
                    type: 'string',
                    value: 'bundle2',
                  },
                  {
                    name: 'number_of_bananas',
                    type: 'number',
                    value: 10,
                  },
                ],
              ],
            },
          ],
          stem: 'PACKAGE_BANANA',
          time: {
            tag: '00:00:10',
            type: 'COMMAND_RELATIVE',
          },
          type: 'command',
        },
      ],
    };
    const actual = JSON.parse(await sequenceToSeqJson(SeqLanguage.parser.parse(seq), seq, commandBanana, id));
    expect(actual).toEqual(expectedJson);
  });

  it('local variables', async () => {
    const id = 'test.sequence';
    const seq = `@ID "test.inline"
@LOCALS L00STR
C ECHO L00STR
C ECHO "L00STR"
C ECHO L01STR
    `;
    const actual = JSON.parse(await sequenceToSeqJson(SeqLanguage.parser.parse(seq), seq, commandBanana, id));
    const expectedJson = {
      id: 'test.inline',
      locals: [
        {
          name: 'L00STR',
          type: 'STRING',
        },
      ],
      metadata: {},
      steps: [
        {
          args: [
            {
              name: 'echo_string',
              type: 'symbol',
              value: 'L00STR',
            },
          ],
          stem: 'ECHO',
          time: {
            type: 'COMMAND_COMPLETE',
          },
          type: 'command',
        },
        {
          args: [
            {
              name: 'echo_string',
              type: 'string',
              value: 'L00STR',
            },
          ],
          stem: 'ECHO',
          time: {
            type: 'COMMAND_COMPLETE',
          },
          type: 'command',
        },
        {
          args: [
            {
              name: 'echo_string',
              type: 'symbol',
              value: 'L01STR',
            },
          ],
          stem: 'ECHO',
          time: {
            type: 'COMMAND_COMPLETE',
          },
          type: 'command',
        },
      ],
    };
    expect(actual).toEqual(expectedJson);
  });

  it('local and parameter block', async () => {
    const id = 'test.sequence';
    const seq = `@ID "test.inline"
@LOCALS_BEGIN
L00STR
L01 INT
L02ENUM ENUM TEMPERATURE "" "hot, cold"
OVEN_ID FLOAT  "-100...-1"
@LOCALS_END

@INPUT_PARAMS_BEGIN
FOOT
SHOE UNIT
SIZE FLOAT "" "small, Large, \\"hello\\""
@INPUT_PARAMS_END

C ECHO L00STR
C ECHO "L01"
C ECHO SIZE
    `;
    const actual = JSON.parse(await sequenceToSeqJson(SeqLanguage.parser.parse(seq), seq, commandBanana, id));
    const expectedJson = {
      id: 'test.inline',
      locals: [
        {
          name: 'L00STR',
          type: 'STRING',
        },
        {
          name: 'L01',
          type: 'INT',
        },
        {
          allowable_values: ['hot', 'cold'],
          enum_name: 'TEMPERATURE',
          name: 'L02ENUM',
          type: 'ENUM',
        },
        {
          allowable_ranges: [{ max: -1, min: -100 }],
          name: 'OVEN_ID',
          type: 'FLOAT',
        },
      ],
      metadata: {},
      parameters: [
        {
          name: 'FOOT',
          type: 'INT',
        },
        {
          name: 'SHOE',
          type: 'UNIT',
        },
        {
          allowable_values: ['small', 'Large', '\\"hello\\"'],
          name: 'SIZE',
          type: 'FLOAT',
        },
      ],
      steps: [
        {
          args: [
            {
              name: 'echo_string',
              type: 'symbol',
              value: 'L00STR',
            },
          ],
          stem: 'ECHO',
          time: {
            type: 'COMMAND_COMPLETE',
          },
          type: 'command',
        },
        {
          args: [
            {
              name: 'echo_string',
              type: 'string',
              value: 'L01',
            },
          ],
          stem: 'ECHO',
          time: {
            type: 'COMMAND_COMPLETE',
          },
          type: 'command',
        },
        {
          args: [
            {
              name: 'echo_string',
              type: 'symbol',
              value: 'SIZE',
            },
          ],
          stem: 'ECHO',
          time: {
            type: 'COMMAND_COMPLETE',
          },
          type: 'command',
        },
      ],
    };
    expect(actual).toEqual(expectedJson);
  });

  it('header ordering', async () => {
    function allPermutations(inputArr: string[]) {
      const result: string[][] = [];
      function permute(arr: string[], m: string[] = []) {
        if (arr.length === 0) {
          result.push(m);
        } else {
          for (let i = 0; i < arr.length; i++) {
            const curr = arr.slice();
            const next = curr.splice(i, 1);
            permute(curr.slice(), m.concat(next));
          }
        }
      }
      permute(inputArr);
      return result;
    }

    const permutations = allPermutations([
      `@ID "test.seq"`,
      `@INPUT_PARAMS L01STR L02STR`,
      `@LOCALS L01INT L02INT L01UINT L02UINT`,
    ]);

    for (const ordering of permutations) {
      const input = ordering.join('\n\n');
      const actual = JSON.parse(await sequenceToSeqJson(SeqLanguage.parser.parse(input), input, commandBanana, 'id'));
      const expected = {
        id: 'test.seq',
        locals: [
          {
            name: 'L01INT',
            type: 'INT',
          },
          {
            name: 'L02INT',
            type: 'INT',
          },
          {
            name: 'L01UINT',
            type: 'UINT',
          },
          {
            name: 'L02UINT',
            type: 'UINT',
          },
        ],
        metadata: {},
        parameters: [
          {
            name: 'L01STR',
            type: 'STRING',
          },
          {
            name: 'L02STR',
            type: 'STRING',
          },
        ],
      };
      expect(actual).toEqual(expected);
    }
  });

  it('Convert quoted strings', async () => {
    const seq = `@ID "escaped_quotes"

    R1 ECHO "Can this handle \\"Escaped\\" quotes??" # and this "too"`;
    const id = 'escaped_quotes';
    const actual = await sequenceToSeqJson(SeqLanguage.parser.parse(seq), seq, commandBanana, id);
    const expected = `{
  "id": "escaped_quotes",
  "metadata": {},
  "steps": [
    {
      "args": [
        {
          "type": "string",
          "value": "Can this handle \\"Escaped\\" quotes??",
          "name": "echo_string"
        }
      ],
      "description": "and this \\"too\\"",
      "stem": "ECHO",
      "time": {
        "tag": "00:00:01",
        "type": "COMMAND_RELATIVE"
      },
      "type": "command"
    }
  ]
}`;
    expect(JSON.parse(actual)).toEqual(JSON.parse(expected));
  });

  it('Convert quoted metadata and models', async () => {
    const seq = `@ID "escaped_metadata"

R00:00:01 ECHO "Can this handle \\"Escaped\\" quotes??" # and this "too"
@METADATA "key" "value"
@METADATA "home" " \\"world\\""
@METADATA "array" [ "\\" quoted ", 1, true, null, "seq" ]
@METADATA "object" { "\\"earth\\"" : "green", "array" : [ "\\" quoted ", 1, true, null, "seq" ]}
@METADATA "this_\\"is\\"_my_key" "This is the value"
@MODEL "Variable" 0 "Offset"
@MODEL "Variable \\"Escaped\\"" 0 "Offset \\" \\" \\"\\""`;
    const id = 'escaped_metadata';
    const actual = await sequenceToSeqJson(SeqLanguage.parser.parse(seq), seq, commandBanana, id);
    const expected = `{
  "id": "escaped_metadata",
  "metadata": {},
  "steps": [
    {
      "args": [
        {
          "type": "string",
          "value": "Can this handle \\"Escaped\\" quotes??",
          "name": "echo_string"
        }
      ],
      "description": "and this \\"too\\"",
      "metadata": {
        "key": "value",
        "home": " \\"world\\"",
        "array": [
          "\\" quoted ",
          1,
          true,
          null,
          "seq"
        ],
        "object": {
          "\\"earth\\"": "green",
          "array": [
            "\\" quoted ",
            1,
            true,
            null,
            "seq"
          ]
        },
        "this_\\"is\\"_my_key": "This is the value"
      },
      "models": [
        {
          "offset": "Offset",
          "value": 0,
          "variable": "Variable"
        },
        {
          "offset": "Offset \\" \\" \\"\\"",
          "value": 0,
          "variable": "Variable \\"Escaped\\""
        }
      ],
      "stem": "ECHO",
      "time": {
        "tag": "00:00:01",
        "type": "COMMAND_RELATIVE"
      },
      "type": "command"
    }
  ]
}`;
    expect(JSON.parse(actual)).toEqual(JSON.parse(expected));
  });

  it('should generate loads, activates, ground blocks', async () => {
    const id = 'test.sequence';
    const seq = `@ID "${id}"
A2024-123T12:34:56 @ACTIVATE("activate.name") # No Args
@ENGINE 10
@EPOCH "epoch string"

A2024-123T12:34:56 @GROUND_BLOCK("ground_block.name") # No Args

A2024-123T12:34:56 @LOAD("load.name") # No Args
@ENGINE 22
@EPOCH "load epoch string"

R123T12:34:56 @LOAD("load2.name") "foobar" 1 2
@ENGINE 5

R123T11:55:33 @GROUND_EVENT("ground_event.name") "foo" 1 2 3

R123T12:34:56 @ACTIVATE("act2.name") "foo" 1 2 3  # Comment text
@ENGINE -1
    `;
    const actual = JSON.parse(await sequenceToSeqJson(SeqLanguage.parser.parse(seq), seq, commandBanana, ''));
    const expectedJson = {
      id,
      metadata: {},
      steps: [
        {
          args: [],
          description: 'No Args',
          engine: 10,
          epoch: 'epoch string',
          sequence: 'activate.name',
          time: {
            tag: '2024-123T12:34:56',
            type: 'ABSOLUTE',
          },
          type: 'activate',
        },
        {
          args: [],
          description: 'No Args',
          name: 'ground_block.name',
          time: {
            tag: '2024-123T12:34:56',
            type: 'ABSOLUTE',
          },
          type: 'ground_block',
        },
        {
          args: [],
          description: 'No Args',
          engine: 22,
          epoch: 'load epoch string',
          sequence: 'load.name',
          time: {
            tag: '2024-123T12:34:56',
            type: 'ABSOLUTE',
          },
          type: 'load',
        },
        {
          args: [
            {
              type: 'string',
              value: 'foobar',
            },
            {
              type: 'number',
              value: 1,
            },
            {
              type: 'number',
              value: 2,
            },
          ],
          engine: 5,
          sequence: 'load2.name',
          time: {
            tag: '123T12:34:56',
            type: 'COMMAND_RELATIVE',
          },
          type: 'load',
        },
        {
          args: [
            {
              type: 'string',
              value: 'foo',
            },
            {
              type: 'number',
              value: 1,
            },
            {
              type: 'number',
              value: 2,
            },
            {
              type: 'number',
              value: 3,
            },
          ],
          name: 'ground_event.name',
          time: {
            tag: '123T11:55:33',
            type: 'COMMAND_RELATIVE',
          },
          type: 'ground_event',
        },
        {
          args: [
            {
              type: 'string',
              value: 'foo',
            },
            {
              type: 'number',
              value: 1,
            },
            {
              type: 'number',
              value: 2,
            },
            {
              type: 'number',
              value: 3,
            },
          ],
          description: 'Comment text',
          engine: -1,
          sequence: 'act2.name',
          time: {
            tag: '123T12:34:56',
            type: 'COMMAND_RELATIVE',
          },
          type: 'activate',
        },
      ],
    };
    expect(actual).toEqual(expectedJson);
  });

  it('should serialize a request with nested metadata', async () => {
    const input = `
A2024-123T12:34:56 @REQUEST_BEGIN("request.name") # Description Text
  C CMD_0 1 2 3
  @METADATA "foo" "bar"
  @MODEL "a" 1 "00:00:00"

  R100 CMD_1 "1 2 3"

@REQUEST_END
@METADATA "sub_object" {
  "boolean": true
}
    `;
    const actual = JSON.parse(await sequenceToSeqJson(SeqLanguage.parser.parse(input), input, commandBanana, 'id'));
    const expected = {
      id: 'id',
      metadata: {},
      requests: [
        {
          description: 'Description Text',
          metadata: {
            sub_object: {
              boolean: true,
            },
          },
          name: 'request.name',
          steps: [
            {
              args: [
                {
                  type: 'number',
                  value: 1,
                },
                {
                  type: 'number',
                  value: 2,
                },
                {
                  type: 'number',
                  value: 3,
                },
              ],
              metadata: { foo: 'bar' },
              models: [{ offset: '00:00:00', value: 1, variable: 'a' }],
              stem: 'CMD_0',
              time: {
                type: 'COMMAND_COMPLETE',
              },
              type: 'command',
            },

            {
              args: [
                {
                  type: 'string',
                  value: '1 2 3',
                },
              ],
              stem: 'CMD_1',
              time: {
                tag: '00:01:40',
                type: 'COMMAND_RELATIVE',
              },
              type: 'command',
            },
          ],
          time: {
            tag: '2024-123T12:34:56',
            type: 'ABSOLUTE',
          },
          type: 'request',
        },
      ],
    };
    expect(actual).toEqual(expected);
  });

  it('should serialize a request with simple metadata', async () => {
    const input = `
G+03:00:00 "GroundEpochName" @REQUEST_BEGIN("request2.name")
  C CMD_0 1 2 3
  @METADATA "cmd_0_meta_name_0" "cmd_0_meta_value_0"
  @MODEL "a" 1 "00:00:00"

  R100 CMD_1 "1 2 3"

@REQUEST_END
@METADATA "req_0_meta_name" "req_0_meta_value"
    `;
    const actual = JSON.parse(await sequenceToSeqJson(SeqLanguage.parser.parse(input), input, commandBanana, 'id'));
    const expected = {
      id: 'id',
      metadata: {},
      requests: [
        {
          ground_epoch: {
            delta: '03:00:00',
            name: 'GroundEpochName',
          },
          metadata: {
            req_0_meta_name: 'req_0_meta_value',
          },
          name: 'request2.name',
          steps: [
            {
              args: [
                {
                  type: 'number',
                  value: 1,
                },
                {
                  type: 'number',
                  value: 2,
                },
                {
                  type: 'number',
                  value: 3,
                },
              ],
              metadata: {
                cmd_0_meta_name_0: 'cmd_0_meta_value_0',
              },
              models: [{ offset: '00:00:00', value: 1, variable: 'a' }],
              stem: 'CMD_0',
              time: {
                type: 'COMMAND_COMPLETE',
              },
              type: 'command',
            },

            {
              args: [
                {
                  type: 'string',
                  value: '1 2 3',
                },
              ],
              stem: 'CMD_1',
              time: {
                tag: '00:01:40',
                type: 'COMMAND_RELATIVE',
              },
              type: 'command',
            },
          ],
          type: 'request',
        },
      ],
    };
    expect(actual).toEqual(expected);
  });

  it('should serialize multiple requests', async () => {
    const input = `
A2024-123T12:34:56 @REQUEST_BEGIN("request.name") # Description Text
  C CMD_0 1 2 3
  @METADATA "foo" "bar"
  @MODEL "a" 1 "00:00:00"

  R100 CMD_1 "1 2 3"

@REQUEST_END
@METADATA "sub_object" {
  "boolean": true
}

G03:00:00 "GroundEpochName" @REQUEST_BEGIN("request2.name")
  C CMD_0 1 2 3
  @METADATA "foo" "bar"
  @MODEL "a" 1 "00:00:00"

  R100 CMD_1 "1 2 3"

@REQUEST_END
@METADATA "foo" "bar"

`;

    const actual = JSON.parse(await sequenceToSeqJson(SeqLanguage.parser.parse(input), input, commandBanana, 'id'));
    const expected = {
      id: 'id',
      metadata: {},
      requests: [
        {
          description: 'Description Text',
          metadata: {
            sub_object: {
              boolean: true,
            },
          },
          name: 'request.name',
          steps: [
            {
              args: [
                {
                  type: 'number',
                  value: 1,
                },
                {
                  type: 'number',
                  value: 2,
                },
                {
                  type: 'number',
                  value: 3,
                },
              ],
              metadata: { foo: 'bar' },
              models: [{ offset: '00:00:00', value: 1, variable: 'a' }],
              stem: 'CMD_0',
              time: {
                type: 'COMMAND_COMPLETE',
              },
              type: 'command',
            },

            {
              args: [
                {
                  type: 'string',
                  value: '1 2 3',
                },
              ],
              stem: 'CMD_1',
              time: {
                tag: '00:01:40',
                type: 'COMMAND_RELATIVE',
              },
              type: 'command',
            },
          ],
          time: {
            tag: '2024-123T12:34:56',
            type: 'ABSOLUTE',
          },
          type: 'request',
        },

        {
          ground_epoch: {
            delta: '03:00:00',
            name: 'GroundEpochName',
          },
          metadata: {
            foo: 'bar',
          },
          name: 'request2.name',
          steps: [
            {
              args: [
                {
                  type: 'number',
                  value: 1,
                },
                {
                  type: 'number',
                  value: 2,
                },
                {
                  type: 'number',
                  value: 3,
                },
              ],
              metadata: { foo: 'bar' },
              models: [{ offset: '00:00:00', value: 1, variable: 'a' }],
              stem: 'CMD_0',
              time: {
                type: 'COMMAND_COMPLETE',
              },
              type: 'command',
            },

            {
              args: [
                {
                  type: 'string',
                  value: '1 2 3',
                },
              ],
              stem: 'CMD_1',
              time: {
                tag: '00:01:40',
                type: 'COMMAND_RELATIVE',
              },
              type: 'command',
            },
          ],
          type: 'request',
        },
      ],
    };
    expect(actual).toEqual(expected);
  });

  it('should handle all time tag types', async () => {
    const seq = `A2029-365T23:20:50 BAKE_BREAD
A2029-365T23:21:51.123 BAKE_BREAD
R00:00:30 BAKE_BREAD
R10 BAKE_BREAD
R00:00:30.500 BAKE_BREAD
E00:06:40.333 BAKE_BREAD
E00:00:10 BAKE_BREAD
E-00:06:40.333 BAKE_BREAD`;
    const id = 'test';
    const expectedJson = {
      id: 'test',
      metadata: {},
      steps: [
        {
          args: [],
          stem: 'BAKE_BREAD',
          time: {
            tag: '2029-365T23:20:50',
            type: 'ABSOLUTE',
          },
          type: 'command',
        },
        {
          args: [],
          stem: 'BAKE_BREAD',
          time: {
            tag: '2029-365T23:21:51.123',
            type: 'ABSOLUTE',
          },
          type: 'command',
        },
        {
          args: [],
          stem: 'BAKE_BREAD',
          time: {
            tag: '00:00:30',
            type: 'COMMAND_RELATIVE',
          },
          type: 'command',
        },
        {
          args: [],
          stem: 'BAKE_BREAD',
          time: {
            tag: '00:00:10',
            type: 'COMMAND_RELATIVE',
          },
          type: 'command',
        },
        {
          args: [],
          stem: 'BAKE_BREAD',
          time: {
            tag: '00:00:30.500',
            type: 'COMMAND_RELATIVE',
          },
          type: 'command',
        },
        {
          args: [],
          stem: 'BAKE_BREAD',
          time: {
            tag: '00:06:40.333',
            type: 'EPOCH_RELATIVE',
          },
          type: 'command',
        },
        {
          args: [],
          stem: 'BAKE_BREAD',
          time: {
            tag: '00:00:10',
            type: 'EPOCH_RELATIVE',
          },
          type: 'command',
        },
        {
          args: [],
          stem: 'BAKE_BREAD',
          time: {
            tag: '-00:06:40.333',
            type: 'EPOCH_RELATIVE',
          },
          type: 'command',
        },
      ],
    };
    const actual = JSON.parse(await sequenceToSeqJson(SeqLanguage.parser.parse(seq), seq, commandDictionary, id));
    expect(actual).toEqual(expectedJson);
  });

  describe('round trip', () => {
    it('should round trip commands', async () => {
      const input = `
  @ID "test.seq"
  C FSW_CMD_1 1e3 2.34
  # comment
  C FSW_CMD_1 0.123 -2.34 # inline description`;

      const seqJson1 = await sequenceToSeqJson(SeqLanguage.parser.parse(input), input, commandDictionary, 'id');
      const seqN1 = await seqJsonToSequence(seqJson1);
      const seqJson2 = await sequenceToSeqJson(SeqLanguage.parser.parse(seqN1), seqN1, commandDictionary, 'id');
      expect(seqJson1).toEqual(seqJson2);
    });

    it('should round trip activates, loads, etc', async () => {
      const input = `
@ID "test.seq"
C FSW_CMD_1 1e3 2.34
# comment
C FSW_CMD_1 0.123 -2.34 # inline description
A2024-123T12:34:56 @REQUEST_BEGIN("request.name") # Description Text
  C CMD_0 1 2 3
  @METADATA "foo" "bar"
  @MODEL "a" 1 "00:00:00"
  R100 CMD_1 "1 2 3"
@REQUEST_END
@METADATA "sub_object" {
  "boolean": true
}
G+3 "GroundEpochName" @REQUEST_BEGIN("request2.name")
  C CMD_0 1 2 3
  @METADATA "foo" "bar"
  @MODEL "a" 1 "00:00:00"
  R100 CMD_1 "1 2 3"
@REQUEST_END
@METADATA "foo" "bar"
  `;

      const seqJson1 = await sequenceToSeqJson(SeqLanguage.parser.parse(input), input, commandDictionary, 'id');
      const seqN1 = await seqJsonToSequence(seqJson1);
      const seqJson2 = await sequenceToSeqJson(SeqLanguage.parser.parse(seqN1), seqN1, commandDictionary, 'id');
      expect(seqJson1).toEqual(seqJson2);
    });
  });
});

it('should serialize a boolean arg', async () => {
  const input = `

C CMD_0 true false [ false true ]
@METADATA "foo" "bar"
@MODEL "a" true "00:00:00"
  `;
  const actual = JSON.parse(await sequenceToSeqJson(SeqLanguage.parser.parse(input), input, commandBanana, 'id'));
  const expected = {
    id: 'id',
    metadata: {},
    steps: [
      {
        args: [
          {
            type: 'boolean',
            value: true,
          },
          {
            type: 'boolean',
            value: false,
          },
          {
            type: 'repeat',
            value: [
              [
                {
                  type: 'boolean',
                  value: false,
                },
                {
                  type: 'boolean',
                  value: true,
                },
              ],
            ],
          },
        ],
        metadata: {
          foo: 'bar',
        },
        models: [
          {
            offset: '00:00:00',
            value: true,
            variable: 'a',
          },
        ],
        stem: 'CMD_0',
        time: {
          type: 'COMMAND_COMPLETE',
        },
        type: 'command',
      },
    ],
  };
  expect(actual).toEqual(expected);
});

describe('parseVariables', () => {
  const seqN = [
    `@LOCALS_BEGIN
TEMPERATURE INT
SIZE INT "-1...20, 40..."
STORE ENUM STORE_NAME "" "MACY, ROSS, BEST_BUY"
CHARGE
@LOCALS_END`,
    `@INPUT_PARAMS_BEGIN
DOOR_ENABLED INT
X_RANGE INT "...10" "-1,0,3,4,5,9"
@INPUT_PARAMS_END`,
  ];

  it('should return undefined if there are no variable children', () => {
    let tree = parser.parse(`${seqN[0]}`);
    let variable = parseVariables(tree.topNode, seqN[0], 'LocalDeclaration');
    expect(variable).toBeDefined();

    expect(variable?.length).toEqual(4);
    if (variable) {
      expect(variable[0]).toEqual({ name: 'TEMPERATURE', type: 'INT' } as VariableDeclaration);
      expect(variable[1]).toEqual({
        allowable_ranges: [
          { max: 20, min: -1 },
          { max: Infinity, min: 40 },
        ],
        name: 'SIZE',
        type: 'INT',
      } as VariableDeclaration);
      expect(variable[2]).toEqual({
        allowable_values: ['MACY', 'ROSS', 'BEST_BUY'],
        enum_name: 'STORE_NAME',
        name: 'STORE',
        type: 'ENUM',
      });
      expect(variable[3]).toEqual({ name: 'CHARGE', type: 'INT' });
    }

    tree = parser.parse(`${seqN[1]}`);
    variable = parseVariables(tree.topNode, seqN[1], 'ParameterDeclaration');
    expect(variable).toBeDefined();

    expect(variable?.length).toEqual(2);
    if (variable) {
      expect(variable[0]).toEqual({ name: 'DOOR_ENABLED', type: 'INT' } as VariableDeclaration);
      expect(variable[1]).toEqual({
        allowable_ranges: [{ max: 10, min: -Infinity }],
        allowable_values: ['-1', '0', '3', '4', '5', '9'],
        name: 'X_RANGE',
        type: 'INT',
      } as VariableDeclaration);
    }
  });
});
