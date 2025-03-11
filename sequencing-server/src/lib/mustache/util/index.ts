/* A wrapper for Handlebars, so that `sequencing-server` isn't polluted with helper registration and the like. */
import Handlebars from "handlebars";
import * as fs from 'fs';
import { addTime as addTimeFull, subtractTime as subtractTimeFull, ISO8061toSTOL, STOLToISO8061, SeqNToISO8061, ISO8061toSeqN } from "./time.js";
import { getEnv } from "../../../env.js";

// initialized to environment variable, but this can be changed at runtime.
const environment = {
    language: getEnv().SEQUENCING_LANGUAGE
}

/////////////// AERIE HELPERS ///////////////
// wrappers for helpers
function addTime(startTime: string, duration: string) {
    return addTimeFull(startTime, duration, environment)
}

function subtractTime(startTime: string, duration: string) {
    return subtractTimeFull(startTime, duration, environment)
}

// helper to flatten out array
function flatten(array: any[]): string {
    if (environment.language === "STOL" || environment.language === "TEXT") {
        return new Handlebars.SafeString(`[${array.join(", ")}]`).toString();
    }
    else {
        return new Handlebars.SafeString(`[${array.join(" ")}]`).toString();
    }
}

// helper to clean dates. must be manually invoked by the user, just in case. here, TEXT and SEQN are handled the same.
function formatAsDate(date: string): string {
    if (environment.language === "STOL") {
        return ISO8061toSTOL(STOLToISO8061(date))
    }
    else {
        return ISO8061toSeqN(SeqNToISO8061(date))
    }
}

// TODO: FIGURE OUT A GOOD WAY TO ALLOW USER UPLOADS/DEFINITION IN AERIE MAIN? Ask about this.
/////////////// USER HELPERS ///////////////
//  Allow the user to provide their own helpers.

// relative to index.ts
const testFolder = './user-defined/';

fs.readdir(testFolder, (err, files) => {
    if (!err) {
        files.forEach(file => {
            if (file.includes(".js")) {
                // relative to mustache.ts
                import("../" + testFolder + file).then((module) => {
                    let moduleName = file.replace(".js", "")
                    let castedFunctions: { [key: string]: any } = module

                    Object.keys(castedFunctions).forEach(funcName => {
                        Handlebars.registerHelper(moduleName + "." + funcName, castedFunctions[funcName])
                    })
                })
            }
        });
    }
});


/////////////// AERIE HELPER REGISTRATION ///////////////
Handlebars.registerHelper("add-time", addTime)
Handlebars.registerHelper("subtract-time", subtractTime)
Handlebars.registerHelper("flatten", flatten)
Handlebars.registerHelper("formatAsDate", formatAsDate)


/////////////// EXPOSE TO SEQUENCING-SERVER ///////////////
export class Mustache {
    private template: HandlebarsTemplateDelegate<any>

    constructor(template: string, language?: string) {
        environment.language = language ?? environment.language
        this.template = Handlebars.compile(template)
    }

    public execute(data: any) {
        // TODO: AUTOMATICALLY FORMAT TIMES IN DATA? Ask about this
        return this.template(data)
    }

    public setLanguage(language: string) {
        environment.language = language
    }

    public getLanguage(): string {
        return environment.language
    }
}
