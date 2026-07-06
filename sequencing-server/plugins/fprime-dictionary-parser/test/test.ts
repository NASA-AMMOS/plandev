import * as fs from 'fs';
import plugin from '../src/fprime-parser.js';
import test from 'node:test';
import assert from 'assert';

test('Unit Test', async () => {
  await test('Parse the dictionary', async () => {
    const dictionary = fs.readFileSync('./test/dictionary/RefTopologyDictionary.json', 'utf8');
    const parsedDictionary = plugin.parseDictionary(dictionary);
    assert.equal(typeof parsedDictionary, 'object');
    assert.notEqual(parsedDictionary.commandDictionary, undefined);
    assert.equal(parsedDictionary.parameterDictionary, undefined);
    assert.equal(parsedDictionary.channelDictionary, undefined);
  });

  await test('Parse dictionary for specific stem', async () => {
    const dictionary = fs.readFileSync('./test/dictionary/MathDeploymentTopologyDictionary.json', 'utf8');
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
});
