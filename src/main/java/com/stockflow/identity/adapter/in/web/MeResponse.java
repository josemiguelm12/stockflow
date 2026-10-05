package com.stockflow.identity.adapter.in.web;

import java.util.UUID;

record MeResponse(UUID id, String email, String role) {
}
