import type { SequencingLanguage } from '../../src/lib/mustache/enums/language.js';
import {
    addTime,
    AERIEDurationToISO8601,
    convertDoyToYmd,
    getDoy,
    InstanttoSeqN,
    InstanttoSTOL,
    SeqNToInstant,
    subtractTime
} from '../../src/lib/mustache/util/time.js';
import { Temporal } from '@js-temporal/polyfill';

describe('Time vector functions', () => {
    it('should increment correctly', () => {
        // arbitrarily formatted as STOL
        let inputTime = '2025-001/12:00:01.0002' // removed Z
        let inputDuration = '1000:00:01.01234Z'

        let result = addTime(inputTime, inputDuration, { language: 'STOL' as SequencingLanguage })
        expect(result).toEqual('2025-043/04:00:02.012540Z')
    });

    it('should decrement correctly', () => {
        // arbitrarily formatted as STOL
        let inputTime = '2025-001/12:00:01.0002Z'
        let inputDuration = '1000:00:01.01234' // removed Z

        let result = subtractTime(inputTime, inputDuration, { language: 'STOL' as SequencingLanguage })
        expect(result).toEqual('2024-325/19:59:59.987860Z')
    });

    it('should handle TEXT correctly', () => {
        // arbitrarily formatted as STOL
        let inputTime = '2025-001T12:00:01.0002Z'
        let inputDuration = '1000:00:01.01234' // removed Z

        let result = subtractTime(inputTime, inputDuration, { language: 'TEXT' as SequencingLanguage })
        expect(result).toEqual('2024-325T19:59:59.987860')
    });

    it('should add negative durations correctly', () => {
        // arbitrarily formatted as STOL
        let inputTime = '2025-001/12:00:01.0002Z'
        let inputDuration = '-1000:00:01.01234' // removed Z

        let result = addTime(inputTime, inputDuration, { language: 'STOL' as SequencingLanguage })
        expect(result).toEqual('2024-325/19:59:59.987860Z')

    });

    it('should subtract negative durations correctly', () => {
        // arbitrarily formatted as STOL
        let inputTime = '2025-001/12:00:01.0002Z'
        let inputDuration = '-1000:00:01.01234' // removed Z

        let result = subtractTime(inputTime, inputDuration, { language: 'STOL' as SequencingLanguage })
        expect(result).toEqual('2025-043/04:00:02.012540Z')
    });
});

describe('Parsing times', () => {
    it('should parse from SeqN', () => {
        let seqNstring = '2025-001T12:00:01.0002Z'
        let instant: Temporal.Instant = SeqNToInstant(seqNstring)

        expect(instant.toString()).toEqual('2025-01-01T12:00:01.0002Z')
    });


    it('should parse from STOL', () => {
        let seqNstring = '2025-001/12:00:01.0002Z'
        let instant: Temporal.Instant = SeqNToInstant(seqNstring)

        expect(instant.toString()).toEqual('2025-01-01T12:00:01.0002Z')
    });

    describe('parsing AERIE Durations', () => {
        it('should parse standard durations', () => {
            let aerieDuration = '12345:67:89.101112Z'
            let ISO8601duration = AERIEDurationToISO8601(aerieDuration)

            expect(ISO8601duration).toEqual('PT12345H67M89.101112S')
        });

        it('should handle microseconds and milliseconds distinctly', () => {
            let aerieDurationMillis = '00:00:00.100'
            let ISO8601durationMillis = AERIEDurationToISO8601(aerieDurationMillis)

            expect(ISO8601durationMillis).toEqual('PT0.100000S')

            let aerieDurationMicros = '00:00:00.01000'
            let ISO8601durationMicros = AERIEDurationToISO8601(aerieDurationMicros)

            expect(ISO8601durationMicros).toEqual('PT0.010000S')
        });

        it('should parse negative durations', () => {
            let negativeDuration = '-00:05:00'
            let negativeISO8601Duration = AERIEDurationToISO8601(negativeDuration)

            expect(negativeISO8601Duration).toEqual('-PT5M')
        });
    });
});

// test conversion back to strings
describe('String conversion', () => {
    describe('convert ISO8601 (Instant) to SeqN', () => {
        it('should convert correctly', () => {
            let instant: Temporal.Instant = Temporal.Instant.from('2025-01-01T12:01:23.000123Z');
            let stolString = InstanttoSeqN(instant)

            expect(stolString).toEqual('2025-001T12:01:23.000123')
        });

        it('should handle padding correctly', () => {
            let normalInstant: Temporal.Instant = Temporal.Instant.from('2025-01-01T12:01:23Z');
            let normalStolString = InstanttoSeqN(normalInstant)

            expect(normalStolString).toEqual('2025-001T12:01:23')

            let msInstant: Temporal.Instant = Temporal.Instant.from('2025-01-01T12:01:23.01Z');
            let msStolString = InstanttoSeqN(msInstant)

            expect(msStolString).toEqual('2025-001T12:01:23.010')

            let usInstant: Temporal.Instant = Temporal.Instant.from('2025-01-01T12:01:23.000010Z');
            let usStolString = InstanttoSeqN(usInstant)

            expect(usStolString).toEqual('2025-001T12:01:23.000010')
        });
    });

    describe('convert ISO8601 (Instant) to STOL', () => {
        it('should convert correctly', () => {
            let instant: Temporal.Instant = Temporal.Instant.from('2025-01-01T12:01:23.000123Z');
            let stolString = InstanttoSTOL(instant)

            expect(stolString).toEqual('2025-001/12:01:23.000123Z')
        });

        it('should handle padding correctly', () => {
            let normalInstant: Temporal.Instant = Temporal.Instant.from('2025-01-01T12:01:23.0Z');
            let normalStolString = InstanttoSTOL(normalInstant)

            expect(normalStolString).toEqual('2025-001/12:01:23Z')

            let msInstant: Temporal.Instant = Temporal.Instant.from('2025-01-01T12:01:23.010Z');
            let msStolString = InstanttoSTOL(msInstant)

            expect(msStolString).toEqual('2025-001/12:01:23.010Z')

            let usInstant: Temporal.Instant = Temporal.Instant.from('2025-01-01T12:01:23.000010Z');
            let usStolString = InstanttoSTOL(usInstant)

            expect(usStolString).toEqual('2025-001/12:01:23.000010Z')
        });
    });
});

describe('AERIE-ui helpers', () => {
    describe('converting DOY to YMD, from multiple formats', () => {
        it('should handle conversion from SeqN DOY', () => {
            let seqnDOY = "2025-001T00:00:00.000123Z"
            expect(convertDoyToYmd(seqnDOY)).toEqual('2025-01-01T00:00:00.000123Z')
        });


        it('should handle conversion from SeqN DOY', () => {
            let stolDOY = "2025-001/00:00:00.000123Z"
            expect(convertDoyToYmd(stolDOY)).toEqual('2025-01-01T00:00:00.000123Z')
        });

        it('should handle conversion from SeqN YMD', () => {
            let seqnYMD = "2025-01-01T00:00:00.000123Z"
            expect(convertDoyToYmd(seqnYMD)).toEqual('2025-01-01T00:00:00.000123Z')
        });

        it('should handle conversion from STOL YMD', () => {
            let stolYMD = "2025-01-01/00:00:00.000123Z"
            expect(convertDoyToYmd(stolYMD)).toEqual('2025-01-01T00:00:00.000123Z')
        });

        it('should operate agnostic of Zulu abbreviation', () => {
            let basicDate = "2025-001T00:00:00"
            expect(convertDoyToYmd(basicDate)).toEqual('2025-01-01T00:00:00Z')
            let seqnDOYnoZ = "2025-001T00:00:00.000123"
            expect(convertDoyToYmd(seqnDOYnoZ)).toEqual('2025-01-01T00:00:00.000123Z')
            let stolDOYnoZ = "2025-001/00:00:00.000123"
            expect(convertDoyToYmd(stolDOYnoZ)).toEqual('2025-01-01T00:00:00.000123Z')
            let seqnYMDnoZ = "2025-01-01T00:00:00.000123"
            expect(convertDoyToYmd(seqnYMDnoZ)).toEqual('2025-01-01T00:00:00.000123Z')
            let stolYMDnoZ = "2025-01-01/00:00:00.000123"
            expect(convertDoyToYmd(stolYMDnoZ)).toEqual('2025-01-01T00:00:00.000123Z')
        });

        it('should fail precisely when handed non-datelike string', () => {
            let gibberish = "abcdefg"
            expect(() => convertDoyToYmd(gibberish))
                .toThrowError(new Error("Given date: abcdefg is an invalid DOY (or YMD) string."))

        });
    });

    it('should extract the day of year', () => {
        let date = new Date('01/03/2025 12:34:45')
        expect(getDoy(date)).toEqual(3)
    });
});
