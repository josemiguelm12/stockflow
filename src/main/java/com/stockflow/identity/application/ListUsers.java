package com.stockflow.identity.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ListUsers {

    static final int MAX_PAGE_SIZE = 100;

    public record UserPage(List<UserAdministrationRepository.UserSummary> items, int page, int size,
                           long totalElements, long totalPages) {
    }

    private final UserAdministrationRepository admins;

    ListUsers(UserAdministrationRepository admins) {
        this.admins = admins;
    }

    /** Orden estable created_at ASC, id ASC. REPEATABLE READ: el total y la página salen de la misma instantánea. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public UserPage list(int page, int size) {
        if (page < 0) {
            throw new InvalidInputException("page", "must be greater than or equal to 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidInputException("size", "must be between 1 and " + MAX_PAGE_SIZE);
        }
        long total = admins.countUsers();
        var items = admins.page(size, (long) page * size);
        long totalPages = total == 0 ? 0 : (total + size - 1) / size;
        return new UserPage(items, page, size, total, totalPages);
    }
}
