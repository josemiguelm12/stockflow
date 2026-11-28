package com.stockflow.report.application;

import com.stockflow.identity.application.InvalidInputException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportRangeTest {

    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    @Test
    void withoutBoundsTheLast30DaysUntilNowAreUsed() {
        ReportRange range = ReportRange.resolve(null, "  ", NOW);

        assertThat(range.to()).isEqualTo(NOW);
        assertThat(range.from()).isEqualTo(NOW.minus(Duration.ofDays(30)));
    }

    @Test
    void aSingleBoundDerivesTheOther() {
        assertThat(ReportRange.resolve("2026-06-01", null, NOW))
                .isEqualTo(new ReportRange(Instant.parse("2026-06-01T00:00:00Z"), NOW));
        assertThat(ReportRange.resolve(null, "2026-06-01T00:00:00Z", NOW).from())
                .isEqualTo(Instant.parse("2026-06-01T00:00:00Z").minus(Duration.ofDays(30)));
    }

    @Test
    void datesAreUtcMidnightAndOffsetsAreNormalisedToUtc() {
        ReportRange range = ReportRange.resolve("2026-03-01", "2026-03-02T02:00:00+02:00", NOW);

        assertThat(range.from()).isEqualTo(Instant.parse("2026-03-01T00:00:00Z"));
        assertThat(range.to()).isEqualTo(Instant.parse("2026-03-02T00:00:00Z"));
    }

    @Test
    void exactly366DaysIsAcceptedAndAnEmptyIntervalIsValid() {
        assertThat(ReportRange.resolve("2025-01-01", "2026-01-02", NOW)).isNotNull(); // 366 días
        assertThat(ReportRange.resolve("2026-03-01", "2026-03-01", NOW).from())
                .isEqualTo(Instant.parse("2026-03-01T00:00:00Z"));
    }

    @Test
    void invalidRangesAreRejected() {
        assertThatThrownBy(() -> ReportRange.resolve("2025-01-01", "2026-01-03", NOW)) // 367 días
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> ReportRange.resolve("2026-03-02", "2026-03-01", NOW))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> ReportRange.resolve("01/03/2026", null, NOW))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> ReportRange.resolve(null, "not-a-date", NOW))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> ReportRange.resolve("2026-03-01T00:00:00", null, NOW)) // sin zona
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> ReportRange.resolve("2027-01-01", null, NOW)) // desde en el futuro => invertido
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void granularityDefaultsToDayAndRejectsUnknownValues() {
        assertThat(ReportGranularity.parse(null)).isEqualTo(ReportGranularity.DAY);
        assertThat(ReportGranularity.parse("month")).isEqualTo(ReportGranularity.MONTH);
        assertThat(ReportGranularity.parse(" WEEK ")).isEqualTo(ReportGranularity.WEEK);
        assertThatThrownBy(() -> ReportGranularity.parse("year")).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> ReportGranularity.parse("day'; DROP TABLE users;--"))
                .isInstanceOf(InvalidInputException.class);
    }
}
