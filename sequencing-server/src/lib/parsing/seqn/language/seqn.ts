import { LRLanguage } from '@codemirror/language';
import { buildParser } from '@lezer/generator';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

function buildLanguage() {
  const grammarPath = path.resolve(__dirname, 'seqn.grammar');
  const grammarText = fs.readFileSync(grammarPath, 'utf8');
  const parser = buildParser(grammarText);
  return LRLanguage.define({
    languageData: {
      commentTokens: { line: '#' },
    },
    parser,
  });
}

export const SeqnLanguage = buildLanguage();
