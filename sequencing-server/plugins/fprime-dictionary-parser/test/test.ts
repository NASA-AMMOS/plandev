import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import plugin from '../src/fprime-parser.js';
import test from 'node:test';
import assert from 'assert';
import type * as ampcs from '@nasa-jpl/aerie-ampcs';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const testDir = __dirname.endsWith('/build/test') ? path.join(__dirname, '../../test') : __dirname;

await test('Unit Test', async () => {
  await test('Parse the dictionary', async () => {
    const dictionary = fs.readFileSync(path.join(testDir, 'dictionary/RefTopologyDictionary.json'), 'utf8');
    const parsedDictionary = plugin.parseDictionary(dictionary);
    assert.equal(typeof parsedDictionary, 'object');
    assert.notEqual(parsedDictionary.commandDictionary, undefined);
    assert.equal(parsedDictionary.parameterDictionary, undefined);
    assert.equal(parsedDictionary.channelDictionary, undefined);
  });

  await test('Parse dictionary for specific stem', async () => {
    const dictionary = fs.readFileSync(path.join(testDir, 'dictionary/MathDeploymentTopologyDictionary.json'), 'utf8');
    const parsedDictionary = plugin.parseDictionary(dictionary);
    assert.equal(typeof parsedDictionary, 'object');
    assert.notEqual(parsedDictionary.commandDictionary, undefined);
    assert.equal(parsedDictionary.parameterDictionary, undefined);
    assert.equal(parsedDictionary.channelDictionary, undefined);
    assert.notEqual(
      parsedDictionary.commandDictionary?.fswCommands.find(cmd => cmd.stem === 'CdhCore.cmdDisp.CMD_TEST_CMD_1'),
      undefined,
    );
  });

  await test('Parse dictionary and verify all stems are present', async () => {
    const dictionary = fs.readFileSync(path.join(testDir, 'dictionary/MathDeploymentTopologyDictionary.json'), 'utf8');
    const parsedDictionary = plugin.parseDictionary(dictionary);

    assert.notEqual(parsedDictionary.commandDictionary, undefined);

    const expectedStems = [
      'CdhCore.cmdDisp.CMD_CLEAR_TRACKING',
      'CdhCore.cmdDisp.CMD_NO_OP',
      'CdhCore.cmdDisp.CMD_NO_OP_STRING',
      'CdhCore.cmdDisp.CMD_TEST_CMD_1',
      'CdhCore.events.DUMP_FILTER_STATE',
      'CdhCore.events.SET_EVENT_FILTER',
      'CdhCore.events.SET_ID_FILTER',
      'CdhCore.health.HLTH_CHNG_PING',
      'CdhCore.health.HLTH_ENABLE',
      'CdhCore.health.HLTH_PING_ENABLE',
      'CdhCore.version.ENABLE',
      'CdhCore.version.VERSION',
      'ComCcsds.comQueue.FLUSH_ALL_QUEUES',
      'ComCcsds.comQueue.FLUSH_QUEUE',
      'ComCcsds.comQueue.SET_QUEUE_PRIORITY',
      'DataProducts.dpCat.BUILD_CATALOG',
      'DataProducts.dpCat.CLEAR_CATALOG',
      'DataProducts.dpCat.START_XMIT_CATALOG',
      'DataProducts.dpCat.STOP_XMIT_CATALOG',
      'DataProducts.dpMgr.CLEAR_EVENT_THROTTLE',
      'DataProducts.dpWriter.CLEAR_EVENT_THROTTLE',
      'FileHandling.fileDownlink.Cancel',
      'FileHandling.fileDownlink.SendFile',
      'FileHandling.fileDownlink.SendPartial',
      'FileHandling.fileManager.AppendFile',
      'FileHandling.fileManager.CalculateCrc',
      'FileHandling.fileManager.CreateDirectory',
      'FileHandling.fileManager.FileSize',
      'FileHandling.fileManager.ListDirectory',
      'FileHandling.fileManager.MoveFile',
      'FileHandling.fileManager.RemoveDirectory',
      'FileHandling.fileManager.RemoveFile',
      'FileHandling.fileManager.ShellCommand',
      'FileHandling.prmDb.PRM_COMMIT_STAGED',
      'FileHandling.prmDb.PRM_LOAD_FILE',
      'FileHandling.prmDb.PRM_SAVE_FILE',
      'MathProject.cmdSeq.CS_AUTO',
      'MathProject.cmdSeq.CS_CANCEL',
      'MathProject.cmdSeq.CS_JOIN_WAIT',
      'MathProject.cmdSeq.CS_MANUAL',
      'MathProject.cmdSeq.CS_RUN',
      'MathProject.cmdSeq.CS_START',
      'MathProject.cmdSeq.CS_STEP',
      'MathProject.cmdSeq.CS_VALIDATE',
      'MathProject.mathReceiver.CLEAR_EVENT_THROTTLE',
      'MathProject.mathReceiver.FACTOR_PRM_SAVE',
      'MathProject.mathReceiver.FACTOR_PRM_SET',
      'MathProject.mathSender.DO_MATH',
      'MathProject.systemResources.ENABLE',
    ];

    const commands = parsedDictionary.commandDictionary!.fswCommands;
    const actualStems = commands.map(cmd => cmd.stem).sort();

    // Verify all expected stems are present
    for (const expectedStem of expectedStems) {
      const foundCommand = commands.find(cmd => cmd.stem === expectedStem);
      assert.notEqual(foundCommand, undefined, `Expected to find command with stem: ${expectedStem}`);
    }

    // Verify the count matches
    assert.equal(
      actualStems.length,
      expectedStems.length,
      `Expected ${expectedStems.length} stems, but found ${actualStems.length}`,
    );

    // Verify exact match of stems
    assert.deepEqual(actualStems, expectedStems, 'Parsed stems should exactly match expected stems');
  });

  await test('Verify argument types for MathProject.mathSender.DO_MATH', async () => {
    const dictionary = fs.readFileSync(path.join(testDir, 'dictionary/MathDeploymentTopologyDictionary.json'), 'utf8');
    const parsedDictionary = plugin.parseDictionary(dictionary);

    assert.notEqual(parsedDictionary.commandDictionary, undefined);

    const command = parsedDictionary.commandDictionary!.fswCommands.find(
      cmd => cmd.stem === 'MathProject.mathSender.DO_MATH',
    );
    assert.notEqual(command, undefined, 'Expected to find MathProject.mathSender.DO_MATH command');

    // Verify command has 3 arguments
    assert.equal(command!.arguments.length, 3, 'Expected 3 arguments');

    // Verify val1 is float
    const val1 = command!.arguments[0] as ampcs.FswCommandArgumentFloat;
    assert.equal(val1.name, 'val1', 'First argument should be named val1');
    assert.equal(val1.arg_type, 'float', 'val1 should be float type');
    assert.equal(val1.bit_length, 32, 'val1 should be 32-bit');
    assert.equal(val1.description, 'The first operand');

    // Verify op is enum
    const op = command!.arguments[1] as ampcs.FswCommandArgumentEnum;
    assert.equal(op.name, 'op', 'Second argument should be named op');
    assert.equal(op.arg_type, 'enum', 'op should be enum type');
    assert.equal(op.enum_name, 'MathProject.MathOp', 'op should reference MathProject.MathOp enum');
    assert.equal(op.description, 'The operation');

    // Verify val2 is float
    const val2 = command!.arguments[2] as ampcs.FswCommandArgumentFloat;
    assert.equal(val2.name, 'val2', 'Third argument should be named val2');
    assert.equal(val2.arg_type, 'float', 'val2 should be float type');
    assert.equal(val2.bit_length, 32, 'val2 should be 32-bit');
    assert.equal(val2.description, 'The second operand');
  });

  await test('Verify argument types for CdhCore.cmdDisp.CMD_TEST_CMD_1', async () => {
    const dictionary = fs.readFileSync(path.join(testDir, 'dictionary/MathDeploymentTopologyDictionary.json'), 'utf8');
    const parsedDictionary = plugin.parseDictionary(dictionary);

    assert.notEqual(parsedDictionary.commandDictionary, undefined);

    const command = parsedDictionary.commandDictionary!.fswCommands.find(
      cmd => cmd.stem === 'CdhCore.cmdDisp.CMD_TEST_CMD_1',
    );
    assert.notEqual(command, undefined, 'Expected to find CdhCore.cmdDisp.CMD_TEST_CMD_1 command');

    // Verify command has 3 arguments
    assert.equal(command!.arguments.length, 3, 'Expected 3 arguments');

    // Verify arg1 is signed integer (I32)
    const arg1 = command!.arguments[0] as ampcs.FswCommandArgumentInteger;
    assert.equal(arg1.name, 'arg1', 'First argument should be named arg1');
    assert.equal(arg1.arg_type, 'integer', 'arg1 should be integer type (signed)');
    assert.equal(arg1.bit_length, 32, 'arg1 should be 32-bit');
    assert.equal(arg1.description, 'The I32 command argument');

    // Verify arg2 is float (F32)
    const arg2 = command!.arguments[1] as ampcs.FswCommandArgumentFloat;
    assert.equal(arg2.name, 'arg2', 'Second argument should be named arg2');
    assert.equal(arg2.arg_type, 'float', 'arg2 should be float type');
    assert.equal(arg2.bit_length, 32, 'arg2 should be 32-bit');
    assert.equal(arg2.description, 'The F32 command argument');

    // Verify arg3 is unsigned integer (U8)
    const arg3 = command!.arguments[2] as ampcs.FswCommandArgumentUnsigned;
    assert.equal(arg3.name, 'arg3', 'Third argument should be named arg3');
    assert.equal(arg3.arg_type, 'unsigned', 'arg3 should be unsigned type');
    assert.equal(arg3.bit_length, 8, 'arg3 should be 8-bit');
    assert.equal(arg3.description, 'The U8 command argument');
  });

  await test('Verify aliases are resolved in command arguments', async () => {
    // Create a minimal dictionary with an alias type and a command that uses it
    const dictionaryWithAlias = {
      metadata: {
        deploymentName: 'TestDeployment',
        dictionarySpecVersion: '1.0',
        projectVersion: '1.0.0',
      },
      typeDefinitions: [
        {
          kind: 'alias',
          qualifiedName: 'CustomIdType',
          underlyingType: {
            name: 'U32',
            kind: 'integer',
            size: 32,
            signed: false,
          },
        },
      ],
      commands: [
        {
          name: 'TestComponent.TEST_ALIAS_CMD',
          commandKind: 'sync',
          opcode: 100,
          annotation: 'Test command with alias parameter',
          formalParams: [
            {
              name: 'customId',
              type: {
                name: 'CustomIdType',
                kind: 'qualifiedIdentifier',
              },
              ref: false,
              annotation: 'A custom ID using an alias type',
            },
          ],
        },
      ],
    };

    const parsedDictionary = plugin.parseDictionary(JSON.stringify(dictionaryWithAlias));
    assert.notEqual(parsedDictionary.commandDictionary, undefined);

    const command = parsedDictionary.commandDictionary!.fswCommands.find(
      cmd => cmd.stem === 'TestComponent.TEST_ALIAS_CMD',
    );
    assert.notEqual(command, undefined, 'Expected to find TestComponent.TEST_ALIAS_CMD command');

    // Verify command has 1 argument
    assert.equal(command!.arguments.length, 1, 'Expected 1 argument');

    // Verify the alias was resolved to its underlying unsigned integer type
    const customId = command!.arguments[0] as ampcs.FswCommandArgumentUnsigned;
    assert.equal(customId.name, 'customId', 'Argument should be named customId');
    assert.equal(customId.arg_type, 'unsigned', 'customId should be resolved to unsigned type');
    assert.equal(customId.bit_length, 32, 'customId should be 32-bit');
    assert.equal(customId.description, 'A custom ID using an alias type');
  });

  await test('Verify nested aliases are recursively resolved', async () => {
    // Create a dictionary with nested aliases (alias of an alias)
    const dictionaryWithNestedAlias = {
      metadata: {
        deploymentName: 'TestDeployment',
        dictionarySpecVersion: '1.0',
        projectVersion: '1.0.0',
      },
      typeDefinitions: [
        {
          kind: 'alias',
          qualifiedName: 'BaseIdType',
          underlyingType: {
            name: 'U64',
            kind: 'integer',
            size: 64,
            signed: false,
          },
        },
        {
          kind: 'alias',
          qualifiedName: 'WrappedIdType',
          underlyingType: {
            name: 'BaseIdType',
            kind: 'qualifiedIdentifier',
          },
        },
      ],
      commands: [
        {
          name: 'TestComponent.TEST_NESTED_ALIAS_CMD',
          commandKind: 'sync',
          opcode: 101,
          annotation: 'Test command with nested alias parameter',
          formalParams: [
            {
              name: 'wrappedId',
              type: {
                name: 'WrappedIdType',
                kind: 'qualifiedIdentifier',
              },
              ref: false,
              annotation: 'A wrapped ID using nested alias types',
            },
          ],
        },
      ],
    };

    const parsedDictionary = plugin.parseDictionary(JSON.stringify(dictionaryWithNestedAlias));
    assert.notEqual(parsedDictionary.commandDictionary, undefined);

    const command = parsedDictionary.commandDictionary!.fswCommands.find(
      cmd => cmd.stem === 'TestComponent.TEST_NESTED_ALIAS_CMD',
    );
    assert.notEqual(command, undefined, 'Expected to find TestComponent.TEST_NESTED_ALIAS_CMD command');

    // Verify command has 1 argument
    assert.equal(command!.arguments.length, 1, 'Expected 1 argument');

    // Verify the nested alias was recursively resolved to the underlying U64 unsigned integer type
    const wrappedId = command!.arguments[0] as ampcs.FswCommandArgumentUnsigned;
    assert.equal(wrappedId.name, 'wrappedId', 'Argument should be named wrappedId');
    assert.equal(wrappedId.arg_type, 'unsigned', 'wrappedId should be resolved to unsigned type');
    assert.equal(wrappedId.bit_length, 64, 'wrappedId should be 64-bit (from BaseIdType -> U64)');
    assert.equal(wrappedId.description, 'A wrapped ID using nested alias types');
  });
});
