import type { VariableDeclaration } from '@nasa-jpl/seq-json-schema/types';
import { readFileSync } from 'fs';
import { describe, expect, it } from 'vitest';
import { SeqLanguage } from '../codemirror';
import { parser } from '../codemirror/sequence.grammar';
import { seqJsonToSequence } from './from-seq-json';
import { parseVariables, sequenceToSeqJson } from './to-seq-json';

describe('convert a sequence to seq json', async () => {
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
