import { 
    addTime, 
    AERIEDurationToISO8061, 
    convertDoyToYmd, 
    getDoy, 
    ISO8061toSeqN, 
    ISO8061toSTOL, 
    SeqNToISO8061, 
    subtractTime 
} from '../../src/lib/mustache/util/time.js';
import { Temporal } from '@js-temporal/polyfill';

describe('Time vector functions', () => {
    it('should increment correctly', () => {
        // arbitrarily formatted as STOL
        let inputTime = '2025-001/12:00:01.0002' // removed Z
        let inputDuration = '1000:00:01.01234Z'

        let result = addTime(inputTime, inputDuration, { language: 'STOL' })
        expect(result).toEqual('2025-043/04:00:02.123600Z')
    });

    it('should decrement correctly', () => {
        // arbitrarily formatted as STOL
        let inputTime = '2025-001/12:00:01.0002Z'
        let inputDuration = '1000:00:01.01234' // removed Z

        let result = subtractTime(inputTime, inputDuration, { language: 'STOL' })
        expect(result).toEqual('2024-325/19:59:59.876800Z')
    });
});

describe('Parsing times', () => {
    it('should parse from SeqN', () => {
        let seqNstring = '2025-001T12:00:01.0002Z'
        let instant: Temporal.Instant = SeqNToISO8061(seqNstring)

        expect(instant.toString()).toEqual('2025-01-01T12:00:01.0002Z')
    });


    it('should parse from STOL', () => {
        let seqNstring = '2025-001/12:00:01.0002Z'
        let instant: Temporal.Instant = SeqNToISO8061(seqNstring)

        expect(instant.toString()).toEqual('2025-01-01T12:00:01.0002Z')
    });

    it('should parse AERIE Durations', () => {
        let aerieDuration = '12345:67:89.101112Z'
        let ISO8061duration = AERIEDurationToISO8061(aerieDuration)

        expect(ISO8061duration).toEqual('PT12345H67M89.101112S')
    });
});

// test conversion back to strings
describe('String conversion', () => {
    describe('convert ISO8061 to SeqN', () => {
        it('should convert correctly', () => {
            let instant: Temporal.Instant = Temporal.Instant.fromEpochMicroseconds(1735732883000123n);
            let stolString = ISO8061toSeqN(instant)
        
            expect(stolString).toEqual('2025-001T12:01:23.000123Z')
        });

        it('should handle padding correctly', () => {
            let normalInstant: Temporal.Instant = Temporal.Instant.fromEpochMicroseconds(1735732883000000n);
            let normalStolString = ISO8061toSeqN(normalInstant)
        
            expect(normalStolString).toEqual('2025-001T12:01:23Z')
        
            let msInstant: Temporal.Instant = Temporal.Instant.fromEpochMicroseconds(1735732883010000n);
            let msStolString = ISO8061toSeqN(msInstant)
        
            expect(msStolString).toEqual('2025-001T12:01:23.010Z')
        
            let usInstant: Temporal.Instant = Temporal.Instant.fromEpochMicroseconds(1735732883000010n);
            let usStolString = ISO8061toSeqN(usInstant)
        
            expect(usStolString).toEqual('2025-001T12:01:23.000010Z')
        });
    });

    describe('convert ISO8061 to STOL', () => {
        it('should convert correctly', () => {
            let instant: Temporal.Instant = Temporal.Instant.fromEpochMicroseconds(1735732883000123n);
            let stolString = ISO8061toSTOL(instant)
        
            expect(stolString).toEqual('2025-001/12:01:23.000123Z')
        });

        it('should handle padding correctly', () => {
            let normalInstant: Temporal.Instant = Temporal.Instant.fromEpochMicroseconds(1735732883000000n);
            let normalStolString = ISO8061toSTOL(normalInstant)

            expect(normalStolString).toEqual('2025-001/12:01:23Z')

            let msInstant: Temporal.Instant = Temporal.Instant.fromEpochMicroseconds(1735732883010000n);
            let msStolString = ISO8061toSTOL(msInstant)

            expect(msStolString).toEqual('2025-001/12:01:23.010Z')

            let usInstant: Temporal.Instant = Temporal.Instant.fromEpochMicroseconds(1735732883000010n);
            let usStolString = ISO8061toSTOL(usInstant)

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

        it('should operate agnostic of Zulu abbrevriation', () => {
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