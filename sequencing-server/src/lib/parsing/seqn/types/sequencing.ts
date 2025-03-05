// Only using: NumberArg, StringArg

import type { SyntaxNode, Tree } from '@lezer/common';
import type {
  FswCommandArgument,
  FswCommandArgumentFixedString,
  FswCommandArgumentFloat,
  FswCommandArgumentInteger,
  FswCommandArgumentNumeric,
  FswCommandArgumentRepeat,
  FswCommandArgumentUnsigned,
  FswCommandArgumentVarString,
} from '@nasa-jpl/aerie-ampcs';
import type { VariableDeclaration } from '@nasa-jpl/seq-json-schema/types';
import type { DictionaryTypes } from '../enums/dictionaryTypes.js';
import type { UserId } from './user.js';

export type ChannelDictionary = {
  type: DictionaryTypes.CHANNEL;
} & DictionaryType;

export type CommandDictionary = {
  type: DictionaryTypes.COMMAND;
} & DictionaryType;

export type ParameterDictionary = {
  type: DictionaryTypes.PARAMETER;
} & DictionaryType;

export type DictionaryType = {
  created_at: string;
  id: number;
  mission: string;
  path: string;
  updated_at: string;
  version: string;
};

export type Parcel = {
  channel_dictionary_id: number | null;
  command_dictionary_id: number;
  created_at: string;
  id: number;
  name: string;
  owner: UserId;
  sequence_adaptation_id: number | null;
  updated_at: string;
};

export type ParcelBundle = {
  command_dictionary_id: number | undefined;
} & Omit<Parcel, 'command_dictionary_id' | 'updated_at'>;

export type ParcelToParameterDictionary = {
  parameter_dictionary_id: number;
  parcel_id: number;
};

export type ParcelInsertInput = Omit<Parcel, 'created_at' | 'id' | 'owner' | 'updated_at'>;

export type GetSeqJsonResponseError = {
  location: {
    column: number;
    line: number;
  };
  message: string;
  stack: string;
};

export type GetSeqJsonResponse = {
  errors: GetSeqJsonResponseError[];
  seqJson: SeqJson;
  status: 'FAILURE' | 'SUCCESS';
};

export type SeqJson = any; // TODO: Strongly type.

export type UserSequence = {
  created_at: string;
  definition: string;
  id: number;
  name: string;
  owner: UserId;
  parcel_id: number;
  seq_json: SeqJson;
  updated_at: string;
  workspace_id: number;
};

export type LibrarySequence = {
  name: string;
  parameters: VariableDeclaration[];
  tree: Tree;
  workspace_id: number;
};

export type UserSequenceInsertInput = Omit<UserSequence, 'created_at' | 'id' | 'owner' | 'updated_at'>;

export type Workspace = {
  created_at: string;
  id: number;
  name: string;
  owner: UserId;
  updated_at: string;
};

export type TimeTagInfo = { node: SyntaxNode; text: string } | null | undefined;

export type StringArg = FswCommandArgumentVarString | FswCommandArgumentFixedString;

export type NumberArg =
  | FswCommandArgumentFloat
  | FswCommandArgumentInteger
  | FswCommandArgumentNumeric
  | FswCommandArgumentUnsigned;

export type ArgTextDef = {
  argDef?: FswCommandArgument;
  children?: ArgTextDef[];
  node?: SyntaxNode;
  parentArgDef?: FswCommandArgumentRepeat;
  text?: string;
};
