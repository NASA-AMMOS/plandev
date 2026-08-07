import './polyfills.js';

import { threadId } from 'worker_threads';

import getLogger from './utils/logger.js';

const logger = getLogger(`[ Worker ${threadId} ]`);
logger.info('Starting worker thread...');

