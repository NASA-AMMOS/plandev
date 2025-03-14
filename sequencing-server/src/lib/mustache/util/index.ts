/* A wrapper for Handlebars, so that `sequencing-server` isn't polluted with helper registration and the like. */
import Handlebars from "handlebars";

// somehow obtained
const environment = {
    language: "SEQN"
}

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
}
