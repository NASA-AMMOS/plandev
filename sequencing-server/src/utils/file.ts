import fs from 'fs';
import { getEnv } from '../env.js';
import path from 'path';

export async function writeFile(fileName: string, folderName: string, data: string): Promise<string> {
  // Replace any character that isn't a-Z, 0-9, ., _, or - with an underscore to sanitize.
  const folderPath = path.join(getEnv().STORAGE, folderName.replace(/[^a-z0-9._-]/gi, '_'));
  const filePath = path.join(folderPath, fileName.replace(/[^a-z0-9._-]/gi, '_'));

  // One of the paths tried to traverse out of the base directory.
  if (!folderPath.startsWith(getEnv().STORAGE) || !filePath.startsWith(getEnv().STORAGE)) {
    throw new Error('Command Dictionary write location invalid');
  }

  await fs.promises.mkdir(folderPath, { recursive: true });

  await fs.promises.writeFile(filePath, data, {
    flag: 'w',
  });

  return filePath;
}
