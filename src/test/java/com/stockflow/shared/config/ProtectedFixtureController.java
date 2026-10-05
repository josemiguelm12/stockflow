package com.stockflow.shared.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Fixture solo de test: demuestra que cualquier ruta no listada queda denegada. */
@RestController
class ProtectedFixtureController {

    @GetMapping("/api/p1-t00/protected-fixture")
    String protectedFixture() {
        return "should never be reachable anonymously";
    }
}
