import { Mustache } from '../../src/lib/mustache/util/index.js';

describe('Basic template functionality', () => {

    // nested in object to demonstrate functionality
    let genericInput = { input: { name: "<name>" } }

    it('can escape inputs in filling out templates', () => {
        // handlebars does automatically escape the input
        let escapedTemplateRaw = 'TEMP {{ input.name }}'

        let escapedTemplate = new Mustache(escapedTemplateRaw)
        expect(escapedTemplate.execute(genericInput))
            .toEqual("TEMP &lt;name&gt;")
    });

    it('can leave inputs unescaped in filling out templates', () => {
        // handlebars does NOT automatically escape the input
        let unescapedTemplateRaw = 'TEMP {{{ input.name }}}'

        let unescapedTemplate = new Mustache(unescapedTemplateRaw)
        expect(unescapedTemplate.execute(genericInput))
            .toEqual("TEMP <name>")
    });

    it('errs if a template doesn\'t close a mustache', () => {
        // should fail
        let badTemplateRaw = 'TEMP {{ bear }'
        
        let badTemplate = new Mustache(badTemplateRaw)
        try {
            console.log(badTemplate.execute(genericInput))
        }
        catch (e) {
            const stringError = String(e);
            console.error(stringError.split("\t")[0])
        }
    });
});

describe('Test built-in helpers', () => {
    describe('Date arithmetic', () => {
        // we test different formats in time.test.ts, so the choice of STOL here is arbitrary
        let input = {
            date: "2025-001/01:02:03.0012Z",
            duration: "00:05:01.1234"
        }
    
        it('should increment correctly', () => {
            let templateRaw = 'Adding dates: {{ add-time date duration }}'
            let template = new Mustache(templateRaw)
            expect(template.execute(input))
                .toEqual('Adding dates: 2025-001/01:07:04.124600Z')
        })
    
        it('should decrement correctly', () => {
            let templateRaw = 'Subtracting dates: {{ subtract-time date duration }}'
            let template = new Mustache(templateRaw)
            expect(template.execute(input))
                .toEqual('Subtracting dates: 2025-001/00:57:01.877800Z')
        })
    });

    it('should flatten arrays correctly', () => {
        // assuming SeqN environment
        let templateRaw = 'Unflattened: {{ firstArraySet }}; Flattened: {{ flatten firstArraySet }}'
        let input = { firstArraySet: [1, 2, 3.5, "string"] }
    
        let template = new Mustache(templateRaw)
        expect(template.execute(input))
            .toEqual('Unflattened: 1,2,3.5,string; Flattened: [1 2 3.5 string]')
    });

    it('should reformat dates correctly', () => {
        let templateRaw = 'Uncleaned: {{ date }}; Cleaned: {{ formatAsDate date }}; Chained {{ formatAsDate (add-time (formatAsDate date) duration) }}'
        let input = { date: "2025-001T00:00:00.00Z", duration: "00:05:00" }
    
        let template = new Mustache(templateRaw)
        expect(template.execute(input))
            .toEqual('Uncleaned: 2025-001T00:00:00.00Z; Cleaned: 2025-001/00:00:00Z; Chained 2025-001/00:05:00Z')
    })
});
