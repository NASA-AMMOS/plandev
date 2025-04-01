
import * as ampcs from '@nasa-jpl/aerie-ampcs';
import type { CommandDictionary, ChannelDictionary, ParameterDictionary } from '@nasa-jpl/aerie-ampcs';

export enum DictionaryHeaders {
  'command_dictionary' = 'command_dictionary',
  'parameter_dictionary' = 'param-def',
  'channel_dictionary' = 'telemetry_dictionary',
}
export default {
  name: 'ampcs-parser',
  version: '1.0.0',
  author: 'Ryan Goetz',
  parseDictionary(commandDictionaryXML : string): {
    commandDictionary?: CommandDictionary,
    channelDictionary?: ChannelDictionary,
    parameterDictionary?: ParameterDictionary
  } {
    const header = commandDictionaryXML.split('\n')[1];
    if (!header) {
      throw new Error(`POST /dictionary: Unsupported dictionary`);
    }

    const dictionaryHeaders = [
      `<${DictionaryHeaders.command_dictionary}>`,
      `<${DictionaryHeaders.channel_dictionary}>`,
      `<${DictionaryHeaders.parameter_dictionary}>`,
    ];
    if (!dictionaryHeaders.includes(header)) {
      throw new Error(`POST /dictionary: Unsupported dictionary`);
    }

    if (header === dictionaryHeaders[0]) {
      return { commandDictionary: ampcs.parse(commandDictionaryXML, null, { ignoreComment: true })};
    }
    else if (header === dictionaryHeaders[1]) {
      return { channelDictionary : ampcs.parseChannelDictionary(commandDictionaryXML, null, { ignoreComment: true })};
    }
    else if (header === dictionaryHeaders[2]) {
      return { parameterDictionary: ampcs.parseParameterDictionary(commandDictionaryXML)};
    }
    return {};
  }
};
