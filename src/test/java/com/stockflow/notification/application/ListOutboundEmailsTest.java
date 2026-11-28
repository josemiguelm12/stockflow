package com.stockflow.notification.application;

import com.stockflow.identity.application.InvalidInputException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListOutboundEmailsTest {

    private static final class FakeQueries implements OutboundEmailQueries {
        String lastState = "unset";
        int lastLimit;
        long lastOffset;

        @Override
        public long count(String state) {
            lastState = state;
            return 45;
        }

        @Override
        public List<OutboundEmailView> page(String state, int limit, long offset) {
            lastState = state;
            lastLimit = limit;
            lastOffset = offset;
            return List.of();
        }
    }

    private final FakeQueries queries = new FakeQueries();
    private final ListOutboundEmails list = new ListOutboundEmails(queries);

    @Test
    void aBlankStateDoesNotFilterAndPagesAreComputed() {
        var page = list.list("  ", 2, 20);

        assertThat(queries.lastState).isNull();
        assertThat(queries.lastLimit).isEqualTo(20);
        assertThat(queries.lastOffset).isEqualTo(40);
        assertThat(page.totalElements()).isEqualTo(45);
        assertThat(page.totalPages()).isEqualTo(3);
    }

    @Test
    void aKnownStatePassesTheFilterThrough() {
        list.list("FAILED", 0, 10);

        assertThat(queries.lastState).isEqualTo("FAILED");
    }

    @Test
    void invalidStatesAndPagingAreRejectedBeforeAnyQuery() {
        assertThatThrownBy(() -> list.list("sent", 0, 10)).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> list.list("DELETED", 0, 10)).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> list.list(null, -1, 10)).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> list.list(null, 0, 0)).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> list.list(null, 0, 101)).isInstanceOf(InvalidInputException.class);
        assertThat(queries.lastState).isEqualTo("unset");
    }
}
