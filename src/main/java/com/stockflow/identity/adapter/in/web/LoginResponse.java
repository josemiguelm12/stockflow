package com.stockflow.identity.adapter.in.web;

record LoginResponse(String accessToken, String tokenType, long expiresIn) {

    @Override
    public String toString() {
        return "LoginResponse[redacted]";
    }
}
