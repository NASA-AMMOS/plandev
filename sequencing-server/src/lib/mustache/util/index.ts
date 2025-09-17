/* A wrapper for Handlebars, so that `sequencing-server` isn't polluted with helper registration and the like. */
import Handlebars from "handlebars";
import { addTime, subtractTime, formatTime } from "./time.js";
import { getEnv } from "../../../env.js";
import { SequencingLanguage } from "../enums/language.js";

// initialized to environment variable, but this can be changed at runtime.
const environment = {
    language: getEnv().SEQUENCING_LANGUAGE
}

// helper to flatten out array
function flatten(array: any[]): string {
    if (environment.language === SequencingLanguage.STOL || environment.language === SequencingLanguage.TEXT) {
        return new Handlebars.SafeString(`[${array.join(", ")}]`).toString();
    }
    else {
        return new Handlebars.SafeString(`[${array.join(" ")}]`).toString();
    }
}

/////////////// AERIE HELPER REGISTRATION ///////////////
Handlebars.registerHelper("add-time", (t, d) => addTime(t, d, environment.language))
Handlebars.registerHelper("subtract-time", (t, d) => subtractTime(t, d, environment.language))
Handlebars.registerHelper("flatten", flatten)
Handlebars.registerHelper("format-as-date", t => formatTime(t, environment.language))


/////////////// EXPOSE TO SEQUENCING-SERVER ///////////////
export class Mustache {
    private template: HandlebarsTemplateDelegate<any>

    constructor(template: string, language?: SequencingLanguage) {
        environment.language = language ?? environment.language
        this.template = Handlebars.compile(template)
    }

    public execute(data: any) {
        return this.template(data)
    }

    public setLanguage(language: SequencingLanguage) {
        environment.language = language
    }

    public getLanguage(): SequencingLanguage {
        return environment.language
    }
}
