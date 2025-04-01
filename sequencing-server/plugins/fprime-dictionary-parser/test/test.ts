import * as fs from 'fs';
import plugin from '../src/fprime-parser.js';
import test from 'node:test';
import assert from 'assert';

test('Unit Test', async () => {
  await test('Parse the dictionary', async () => {
    const dictionary = fs.readFileSync('./test/dictionary/RefTopologyDictionary.json', 'utf8');
    const parsedDictionary = plugin.parseDictionary(dictionary);
    assert.equal(typeof parsedDictionary, 'object');
    assert.notEqual(parsedDictionary.commandDictionary,undefined);
    assert.equal(parsedDictionary.parameterDictionary,undefined);
    assert.equal(parsedDictionary.channelDictionary,undefined);
  });
});
