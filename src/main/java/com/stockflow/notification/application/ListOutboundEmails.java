package com.stockflow.notification.application;

import com.stockflow.identity.application.InvalidInputException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/** RF-NOT-11: estado de las entregas para un ADMIN (la autorización la impone SecurityConfig). */
@Service
public class ListOutboundEmails {

    static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> STATES = Set.of("PENDING", "SENT", "FAILED");

    public record OutboundEmailPage(List<OutboundEmailQueries.OutboundEmailView> items, int page, int size,
                                    long totalElements, long totalPages) {
    }

    private final OutboundEmailQueries queries;

    ListOutboundEmails(OutboundEmailQueries queries) {
        this.queries = queries;
    }

    /** {@code state} nulo o vacío no filtra. REPEATABLE READ: total y página salen de la misma instantánea. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OutboundEmailPage list(String state, int page, int size) {
        String filter = state == null || state.isBlank() ? null : state.trim();
        if (filter != null && !STATES.contains(filter)) {
            throw new InvalidInputException("state", "must be one of PENDING, SENT, FAILED");
        }
        if (page < 0) {
            throw new InvalidInputException("page", "must be greater than or equal to 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidInputException("size", "must be between 1 and " + MAX_PAGE_SIZE);
        }
        long total = queries.count(filter);
        var items = queries.page(filter, size, (long) page * size);
        long totalPages = total == 0 ? 0 : (total + size - 1) / size;
        return new OutboundEmailPage(items, page, size, total, totalPages);
    }
}
