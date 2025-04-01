import path from 'path';
import { getEnv } from '../env.js';

export class PluginManager {
  private DIRECTORY = "/app/plugins/";
  private plugins: Map<string, any> = new Map();
  async loadPlugins() {
      const filePath = path.join(this.DIRECTORY, getEnv().DICTIONARY_PARSER_PLUGIN);
      try {
        const pluginModule = await import(filePath);
        const plugin = pluginModule.default;
        if (!plugin || !plugin.name || !('parseDictionary' in plugin)) {
          if (!('parseDictionary' in plugin)){
            console.error(`Invalid plugin format: ${plugin.name} at ${filePath}: Missing 'parseDictionary(commandDictionary : string)' function`);
          }else {
            console.error(`Invalid plugin format: ${plugin.name} at ${filePath}`);
          }
        }else{
          console.log(`Loaded plugin: ${plugin.name} from ${filePath}`);
          this.plugins.set(getEnv().DICTIONARY_PARSER_PLUGIN, plugin);
        }
      } catch (error) {
        console.error(`Error loading plugin: ${filePath}`, error);
      }
  }

  getPlugin(name: string) : any | undefined {
    return this.plugins.get(name);
  }

  hasPlugin(name: string) : boolean {
    return this.plugins.has(name);
  }
}
