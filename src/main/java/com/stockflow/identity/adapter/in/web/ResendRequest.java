package com.stockflow.identity.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

record ResendRequest(@NotBlank @Size(max = 400) String email) {
}
