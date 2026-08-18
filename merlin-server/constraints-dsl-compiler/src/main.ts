import './libs/polyfills.js'
import fs from 'fs';
import ts from 'typescript';
import { UserCodeRunner } from '@nasa-jpl/aerie-ts-user-code-runner';
import type { Constraint } from './libs/constraints-edsl-fluent-api.js';
import * as readline from 'readline';
import vm from "node:vm";

const codeRunner = new UserCodeRunner();
const constraintsEDSL = fs.readFileSync(
  `${process.env['CONSTRAINTS_DSL_COMPILER_ROOT']}/src/libs/constraints-edsl-fluent-api.ts`,
  'utf8',
);
const constraintsAST = fs.readFileSync(
  `${process.env['CONSTRAINTS_DSL_COMPILER_ROOT']}/src/libs/constraints-ast.ts`,
  'utf8',
);
const temporalPolyfillTypes = fs.readFileSync(
    `${process.env['CONSTRAINTS_DSL_COMPILER_ROOT']}/src/libs/TemporalPolyfillTypes.ts`,
    'utf8',
);
const tsConfig = JSON.parse(fs.readFileSync(new URL('../tsconfig.json', import.meta.url).pathname, 'utf-8'));
const { options } = ts.parseJsonConfigFileContent(tsConfig, ts.sys, '');
const compilerTarget = options.target ?? ts.ScriptTarget.ES2021

process.on('uncaughtException', err => {
  console.error('uncaughtException');
  console.error(err && err.stack ? err.stack : err);
  process.stdout.write('panic\n' + err.stack ?? err.message);
  process.exit(1);
});

const lineReader = readline.createInterface({
  input: process.stdin,
});
lineReader.once('line', handleRequest);

interface AstNode {
  __astNode: object
}

function toJson(unwrappedErr: any){
  var completeStackValue = "";
  if ('error' in unwrappedErr && 'stack' in unwrappedErr.error){
    completeStackValue = JSON.stringify(unwrappedErr.error.stack)
  }
  return {
    message: unwrappedErr.message,
    stack: unwrappedErr.stack,
    location: unwrappedErr.location,
    completeStack:  completeStackValue,
  }
}

async function handleRequest(data: Buffer) {
  try {
    // Test the health of the service by responding to "ping" with "pong".
    if (data.toString() === 'ping') {
      process.stdout.write('pong\n');
      lineReader.once('line', handleRequest);
      return;
    }
    const { constraintCode, missionModelGeneratedCode, expectedReturnType } = JSON.parse(data.toString()) as {
      constraintCode: string;
      missionModelGeneratedCode: string;
      expectedReturnType: string;
    };

    const additionalSourceFiles: { 'filename': string, 'contents': string}[] = [
      { 'filename': 'constraints-ast.ts', 'contents': constraintsAST },
      { 'filename': 'constraints-edsl-fluent-api.ts', 'contents': constraintsEDSL },
      { 'filename': 'mission-model-generated-code.ts', 'contents': missionModelGeneratedCode },
      { 'filename': 'TemporalPolyfillTypes.ts', 'contents': temporalPolyfillTypes }
    ];

    const result = await codeRunner.executeUserCode<[], AstNode>(
        constraintCode,
        [],
        expectedReturnType,
        [],
        10000,
        additionalSourceFiles.map(({filename, contents}) => ts.createSourceFile(filename, contents, compilerTarget)),
        vm.createContext({
          Temporal,
        }),
    );

    if (result.isErr()) {
      const secondLine = JSON.stringify(result.unwrapErr().map(err => toJson(err))) + '\n';
      process.stdout.write('error\n')
      process.stdout.write(secondLine);
      lineReader.once('line', handleRequest);
      return;
    }

    const stringified = JSON.stringify(
        result.unwrap().__astNode,
        function replacer(key, value) {
          if (this[key] instanceof Temporal.Duration) {
            return this[key].total({ unit: "microseconds" });
          }
          return value;
        }
    );
    if (stringified === undefined) {
      throw Error(JSON.stringify(result.unwrap()) + ' was not JSON serializable');
    }
    process.stdout.write('success\n')
    process.stdout.write(stringified + '\n');
  } catch (error: any) {
    process.stdout.write('panic\n');
    process.stdout.write(JSON.stringify(error.stack ?? error.message) + ' attempted to handle: ' + data.toString() + '\n');
  }
  lineReader.once('line', handleRequest);
}
