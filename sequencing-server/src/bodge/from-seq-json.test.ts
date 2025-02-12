import type { SeqJson } from '@nasa-jpl/seq-json-schema/types';
import { describe, expect, it } from 'vitest';
import { seqJsonToSequence } from './from-seq-json';

describe('from-seq-json.ts', async () => {
  it('converts a seq json id and metadata to sequence', async () => {
    const seqJson: SeqJson = {
      id: 'test',
      metadata: {
        onboard_name: 'test.mod',
        onboard_path: '/eng',
        other_arbitrary_metadata: 'test_metadata',
      },
    };
    const sequence = await seqJsonToSequence(JSON.stringify(seqJson));
    const expectedSequence = `@ID "test"
@METADATA "onboard_name" "test.mod"
@METADATA "onboard_path" "/eng"
@METADATA "other_arbitrary_metadata" "test_metadata"
`;
    expect(sequence).toEqual(expectedSequence);
  });

  it('Symbols should not be quoted', async () => {
    const seqJson: SeqJson = {
      id: 'testSymbol',
      locals: [
        {
          name: 'L00UINT',
          type: 'UINT',
        },
        {
          name: 'L00INT',
          type: 'INT',
        },
        {
          name: 'L01INT',
          type: 'INT',
        },
      ],
      metadata: {},

      steps: [
        {
          args: [
            {
              type: 'symbol',
              value: 'L00UINT',
            },
            {
              type: 'number',
              value: 10,
            },
          ],
          description: 'line argument',
          stem: 'PYRO_FIRE',

          time: {
            type: 'COMMAND_COMPLETE',
          },
          type: 'command',
        },
        {
          args: [
            {
              type: 'symbol',
              value: 'L00INT',
            },
            {
              type: 'symbol',
              value: 'L01INT',
            },
          ],
          stem: 'DDM_BANANA',
          time: {
            type: 'COMMAND_COMPLETE',
          },
          type: 'command',
        },
      ],
    };
    const sequence = await seqJsonToSequence(JSON.stringify(seqJson));
    const expectedSequence = `@ID "testSymbol"
@LOCALS_BEGIN
L00UINT UINT
L00INT INT
L01INT INT
@LOCALS_END

C PYRO_FIRE L00UINT 10 # line argument
C DDM_BANANA L00INT L01INT
`;
    expect(sequence).toEqual(expectedSequence);
  });

  it('converts a seq json LGO to sequence', async () => {
    const seqJson: SeqJson = {
      id: 'test',
      metadata: {
        lgo: true,
        onboard_name: 'test.mod',
        onboard_path: '/eng',
        other_arbitrary_metadata: 'test_metadata',
      },
      steps: [
        {
          args: [],
          stem: 'FSW_CMD_3',
          time: {
            type: 'COMMAND_COMPLETE',
          },
          type: 'command',
        },
      ],
    };
    const sequence = await seqJsonToSequence(JSON.stringify(seqJson));
    const expectedSequence = `@ID "test"
@METADATA "onboard_name" "test.mod"
@METADATA "onboard_path" "/eng"
@METADATA "other_arbitrary_metadata" "test_metadata"

@LOAD_AND_GO
C FSW_CMD_3
`;
    expect(sequence).toEqual(expectedSequence);
  });

  it('converts a seq json variables to sequence', async () => {
    const seqJson: SeqJson = {
      id: 'testVariable',

      locals: [
        {
          name: 'L00INT',
          type: 'INT',
        },
        {
          name: 'L01STR',
          type: 'STRING',
        },
        {
          name: 'L02FLT',
          type: 'FLOAT',
        },
        {
          name: 'L03UINT',
          type: 'UINT',
        },
        {
          name: 'L01ENUM',
          type: 'ENUM',
        },
      ],
      metadata: {
        lgo: false,
      },
      parameters: [
        {
          name: 'L00INT',
          type: 'INT',
        },
        {
          name: 'L01STR',
          type: 'STRING',
        },
        {
          name: 'L02FLT',
          type: 'FLOAT',
        },
        {
          name: 'L03UINT',
          type: 'UINT',
        },
        {
          name: 'L01ENUM',
          type: 'ENUM',
        },
      ],
    };
    const sequence = await seqJsonToSequence(JSON.stringify(seqJson));
    const expectedSequence = `@ID "testVariable"
@INPUT_PARAMS_BEGIN
L00INT INT
L01STR STRING
L02FLT FLOAT
L03UINT UINT
L01ENUM ENUM
@INPUT_PARAMS_END
@LOCALS_BEGIN
L00INT INT
L01STR STRING
L02FLT FLOAT
L03UINT UINT
L01ENUM ENUM
@LOCALS_END
`;
    expect(sequence).toEqual(expectedSequence);
  });

  it('converts a seq json file to a correct sequence', async () => {
    const seqJson: SeqJson = {
      id: '42',
      metadata: {},
      steps: [
        {
          args: [
            {
              type: 'boolean',
              value: true,
            },
            {
              type: 'hex',
              value: '0xFF',
            },
            {
              type: 'string',
              value: 'Hello',
            },
            {
              type: 'symbol',
              value: 'World',
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
                    type: 'hex',
                    value: '0xAA',
                  },
                  {
                    type: 'string',
                    value: 'Foo',
                  },
                  {
                    type: 'symbol',
                    value: 'BAR',
                  },
                ],
                [
                  {
                    type: 'boolean',
                    value: true,
                  },
                  {
                    type: 'hex',
                    value: '0xBB',
                  },
                  {
                    type: 'string',
                    value: 'Baz',
                  },
                  {
                    type: 'symbol',
                    value: 'BAT',
                  },
                ],
              ],
            },
          ],
          stem: 'FSW_CMD_0',
          time: {
            tag: '2024-001T00:00:00',
            type: 'ABSOLUTE',
          },
          type: 'command',
        },
        {
          args: [{ type: 'number', value: 22 }],
          stem: 'FSW_CMD_1',
          time: {
            tag: '00:01:00',
            type: 'COMMAND_RELATIVE',
          },
          type: 'command',
        },
        {
          args: [{ type: 'string', value: 'Fab' }],
          stem: 'FSW_CMD_2',
          time: {
            tag: '15:00:00',
            type: 'EPOCH_RELATIVE',
          },
          type: 'command',
        },
        {
          args: [],
          stem: 'FSW_CMD_3',
          time: {
            type: 'COMMAND_COMPLETE',
          },
          type: 'command',
        },
      ],
    };
    const sequence = await seqJsonToSequence(JSON.stringify(seqJson));
    const expectedSequence = `@ID "42"

A2024-001T00:00:00 FSW_CMD_0 true 0xFF "Hello" World [false 0xAA "Foo" BAR true 0xBB "Baz" BAT]
R00:01:00 FSW_CMD_1 22
E15:00:00 FSW_CMD_2 "Fab"
C FSW_CMD_3
`;
    expect(sequence).toEqual(expectedSequence);
  });

  it('converts a seq json command model and metadata to sequence', async () => {
    const seqJson: SeqJson = {
      id: 'testCommandModeling',
      metadata: {
        onboard_name: 'test.mod',
      },
      steps: [
        {
          args: [
            {
              type: 'string',
              value: 'test',
            },
          ],
          metadata: {
            Key1: 'Value1',
            Key2: 'Value2',
          },
          models: [
            {
              offset: '00:00:00',
              value: 0,
              variable: 'temp',
            },
            {
              offset: '00:00:01',
              value: true,
              variable: 'temp1',
            },
            {
              offset: '00:00:02',
              value: false,
              variable: 'temp2',
            },
            {
              offset: '00:00:03',
              value: 'NULL',
              variable: 'temp4',
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
    const sequence = await seqJsonToSequence(JSON.stringify(seqJson));
    const expectedSequence = `@ID "testCommandModeling"
@METADATA "onboard_name" "test.mod"

C ECHO "test"
@METADATA "Key1" "Value1"
@METADATA "Key2" "Value2"
@MODEL "temp" 0 "00:00:00"
@MODEL "temp1" true "00:00:01"
@MODEL "temp2" false "00:00:02"
@MODEL "temp4" "NULL" "00:00:03"
`;
    expect(sequence).toEqual(expectedSequence);
  });

  it('converts a seq json description to sequence', async () => {
    const seqJson: SeqJson = {
      id: 'testDescription',
      metadata: {},
      steps: [
        {
          args: [
            {
              type: 'string',
              value: 'TEST1',
            },
          ],
          description: 'a description',
          stem: 'ECHO',
          time: {
            type: 'COMMAND_COMPLETE',
          },
          type: 'command',
        },
        {
          args: [],
          description: 'fsw command description',
          models: [
            {
              offset: '00:00:00',
              value: true,
              variable: 'cmd',
            },
          ],
          stem: 'FSW_CMD',
          time: {
            tag: '00:00:01',
            type: 'COMMAND_RELATIVE',
          },
          type: 'command',
        },
        {
          args: [],
          stem: 'FSW_CMD_1',
          time: {
            type: 'COMMAND_COMPLETE',
          },
          type: 'command',
        },
        {
          args: [
            {
              type: 'number',
              value: 10,
            },
            {
              type: 'string',
              value: 'ENUM',
            },
          ],
          description: 'fsw cmd 2 description',
          stem: 'FSW_CMD_2',
          time: {
            type: 'COMMAND_COMPLETE',
          },
          type: 'command',
        },
      ],
    };

    const sequence = await seqJsonToSequence(JSON.stringify(seqJson));
    const expectedSequence = `@ID "testDescription"

C ECHO "TEST1" # a description
R00:00:01 FSW_CMD # fsw command description
@MODEL "cmd" true "00:00:00"
C FSW_CMD_1
C FSW_CMD_2 10 "ENUM" # fsw cmd 2 description
`;
    expect(sequence).toEqual(expectedSequence);
  });

  it('converts a seq json immediate commands to sequence', async () => {
    const seqJson: SeqJson = {
      id: 'testImmediate',
      immediate_commands: [
        {
          args: [
            { type: 'string', value: '1' },
            { type: 'number', value: 2 },
            { type: 'number', value: 3.0 },
          ],
          description: 'immediate command',
          metadata: {},
          stem: 'IC',
        },
        {
          args: [],
          stem: 'IC2',
        },
        {
          args: [],
          description: 'noop command, no arguments',
          metadata: { processor: 'VC1A' },
          stem: 'NOOP',
        },
      ],
      metadata: {},
    };

    const sequence = await seqJsonToSequence(JSON.stringify(seqJson));
    const expectedSequence = `@ID "testImmediate"

@IMMEDIATE
IC "1" 2 3 # immediate command
IC2
NOOP # noop command, no arguments
@METADATA "processor" "VC1A"
`;
    expect(sequence).toEqual(expectedSequence);
  });

  it('converts a seq json hardware commands to sequence', async () => {
    const seqJson: SeqJson = {
      hardware_commands: [
        {
          description: 'hardware command',
          metadata: {
            foo: 'bar',
            hardware: 'HWC',
          },
          stem: 'HWC',
        },
        {
          stem: 'HWC2',
        },
        {
          stem: 'HWC3',
        },
      ],
      id: 'testHardware',
      metadata: {},
    };

    const sequence = await seqJsonToSequence(JSON.stringify(seqJson));
    const expectedSequence = `@ID "testHardware"

@HARDWARE
HWC # hardware command
@METADATA "foo" "bar"
@METADATA "hardware" "HWC"
HWC2
HWC3
`;
    expect(sequence).toEqual(expectedSequence);
  });

  it('converts a seq json time tags to sequence', async () => {
    const seqJson: SeqJson = {
      id: 'testTime',
      metadata: {},
      steps: [
        {
          args: [],
          stem: 'FSA_CMD',
          time: {
            tag: '2020-173T20:00:00.000',
            type: 'ABSOLUTE',
          },
          type: 'command',
        },
        {
          args: [],
          stem: 'FSR_CMD',
          time: { tag: '00:00:10.000', type: 'COMMAND_RELATIVE' },
          type: 'command',
        },
        {
          args: [
            {
              type: 'number',
              value: 10,
            },
            {
              type: 'string',
              value: 'ENUM',
            },
          ],
          stem: 'FSE_CMD',
          time: { tag: '-00:00:01.000', type: 'EPOCH_RELATIVE' },
          type: 'command',
        },
      ],
    };

    const sequence = await seqJsonToSequence(JSON.stringify(seqJson));
    const expectedSequence = `@ID "testTime"

A2020-173T20:00:00.000 FSA_CMD
R00:00:10.000 FSR_CMD
E-00:00:01.000 FSE_CMD 10 "ENUM"
`;
    expect(sequence).toEqual(expectedSequence);
  });

  it('should convert activate', async () => {
    const seqJson: SeqJson = {
      id: 'id',
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
      ],
    };

    const sequence = await seqJsonToSequence(JSON.stringify(seqJson));
    const expectedSequence = `
@ID "id"

A2024-123T12:34:56 @ACTIVATE("activate.name") # No Args
@ENGINE 10
@EPOCH "epoch string"
`;
    expect(sequence.trim()).toEqual(expectedSequence.trim());
  });

  it('should convert load', async () => {
    const seqJson: SeqJson = {
      id: 'id',
      metadata: {},
      steps: [
        {
          args: [],
          engine: 10,
          epoch: 'epoch string',
          sequence: 'load.name',
          time: {
            tag: '2024-123T12:34:56',
            type: 'ABSOLUTE',
          },
          type: 'load',
        },
      ],
    };

    const sequence = await seqJsonToSequence(JSON.stringify(seqJson));
    const expectedSequence = `
@ID "id"

A2024-123T12:34:56 @LOAD("load.name")
@ENGINE 10
@EPOCH "epoch string"
`;
    expect(sequence.trim()).toEqual(expectedSequence.trim());
  });

  it('should convert ground event', async () => {
    const seqJson: SeqJson = {
      id: 'id',
      metadata: {},
      steps: [
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
      ],
    };

    const sequence = await seqJsonToSequence(JSON.stringify(seqJson));

    const expectedSequence = `
@ID "id"

R123T11:55:33 @GROUND_EVENT("ground_event.name") "foo" 1 2 3
`;
    expect(sequence.trim()).toEqual(expectedSequence.trim());
  });

  it('converts a seq json empty repeat args to sequence', async () => {
    const seqJson: SeqJson = {
      id: 'testRepeat',
      metadata: {},
      steps: [
        {
          args: [
            {
              name: 'lot_number',
              type: 'number',
              value: 10,
            },
            {
              name: 'bundle',
              type: 'repeat',
              value: [],
            },
            {
              name: 'country_origin',
              type: 'string',
              value: 'USA',
            },
            {
              name: 'postal_code',
              type: 'repeat',
              value: [
                [
                  {
                    name: 'region',
                    type: 'string',
                    value: '96707-898',
                  },
                ],
                [
                  {
                    name: 'region',
                    type: 'string',
                    value: '92604-623',
                  },
                ],
              ],
            },
          ],
          stem: 'FSA_CMD',
          time: {
            type: 'COMMAND_COMPLETE',
          },
          type: 'command',
        },
      ],
    };

    const sequence = await seqJsonToSequence(JSON.stringify(seqJson));
    const expectedSequence = `@ID "testRepeat"

C FSA_CMD 10 [] "USA" ["96707-898" "92604-623"]
`;
    expect(sequence).toEqual(expectedSequence);
  });

  it('should convert requests to seq format', async () => {
    const seqJson: SeqJson = {
      id: 'id',
      metadata: {},
      requests: [
        {
          ground_epoch: {
            delta: '+3:00',
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
    const sequence = await seqJsonToSequence(JSON.stringify(seqJson));
    const expectedSequence = `
    @ID "id"
    G+3:00 "GroundEpochName" @REQUEST_BEGIN("request2.name")
      C CMD_0 1 2 3
      @METADATA "cmd_0_meta_name_0" "cmd_0_meta_value_0"
      @MODEL "a" 1 "00:00:00"
      R00:01:40 CMD_1 "1 2 3"
    @REQUEST_END
    @METADATA "req_0_meta_name" "req_0_meta_value"
    `;
    expect(normalizeWhitespace(sequence)).toEqual(normalizeWhitespace(expectedSequence));
  });

  it('converts a quoted string', async () => {
    const seqJson: SeqJson = {
      id: 'escaped_quotes',
      metadata: {},
      steps: [
        {
          args: [
            {
              type: 'string',
              value: 'Can this handle " Escaped" quotes??',
            },
          ],
          description: 'Can this handle "escape"',
          stem: 'ECHO',
          time: {
            type: 'COMMAND_COMPLETE',
          },
          type: 'command',
        },
        {
          args: [
            {
              type: 'string',
              value: '"Can" this handle leading and trailing Escaped" quotes??"',
            },
          ],
          description: '"Can" "this" handle "escape"',
          stem: 'ECHO2',
          time: {
            type: 'COMMAND_COMPLETE',
          },
          type: 'command',
        },
      ],
    };

    const sequence = await seqJsonToSequence(JSON.stringify(seqJson));
    const expectedSequence = `@ID "escaped_quotes"

C ECHO "Can this handle \\" Escaped\\" quotes??" # Can this handle "escape"
C ECHO2 "\\"Can\\" this handle leading and trailing Escaped\\" quotes??\\"" # "Can" "this" handle "escape"
`;
    expect(sequence).toEqual(expectedSequence);
  });

  it('BooleanArguments to sequence', async () => {
    const seqJson: SeqJson = {
      id: 'test',
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
          ],
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
    const sequence = await seqJsonToSequence(JSON.stringify(seqJson));
    const expectedSequence = `@ID "test"

C CMD_0 true false
@MODEL "a" true "00:00:00"
`;
    expect(sequence).toEqual(expectedSequence);
  });
});

function normalizeWhitespace(s: string) {
  return s
    .replace(/[ \t]+/g, ' ')
    .replace(/\s*\n\s*/g, '\n')
    .trim();
}
