package com.stockflow.identity.adapter.in.web;

import java.util.UUID;

record RegisterResponse(UUID id, String email, String accountStatus) {
}
