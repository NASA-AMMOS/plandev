/* A wrapper for Handlebars, so that `sequencing-server` isn't polluted with helper registration and the like. */
import Handlebars from "handlebars";
import { addTime as addTimeFull, subtractTime as subtractTimeFull, InstanttoSTOL, STOLToInstant, SeqNToInstant, InstanttoSeqN, TextToISO8601 } from "./time.js";
import { getEnv } from "../../../env.js";
import { SequencingLanguage } from "../enums/language.js";

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
    if (environment.language === SequencingLanguage.STOL || environment.language === SequencingLanguage.TEXT) {
        return new Handlebars.SafeString(`[${array.join(", ")}]`).toString();
    }
    else {
        return new Handlebars.SafeString(`[${array.join(" ")}]`).toString();
    }
}

// helper to clean dates. must be manually invoked by the user, just in case. here, Text and SeqN are handled the same.
function formatAsDate(date: string): string {
    if (environment.language === SequencingLanguage.STOL) {
        return InstanttoSTOL(STOLToInstant(date))
    }
    else if (environment.language === SequencingLanguage.TEXT) {
        return TextToISO8601(date)
    }
    else {
        return InstanttoSeqN(SeqNToInstant(date))
    }
}

/////////////// AERIE HELPER REGISTRATION ///////////////
Handlebars.registerHelper("add-time", addTime)
Handlebars.registerHelper("subtract-time", subtractTime)
Handlebars.registerHelper("flatten", flatten)
Handlebars.registerHelper("format-as-date", formatAsDate)


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
