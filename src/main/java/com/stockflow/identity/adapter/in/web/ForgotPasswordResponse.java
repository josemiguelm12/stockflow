package com.stockflow.identity.adapter.in.web;

/** Cuerpo idéntico exista o no la cuenta y sea cual sea su estado, para no permitir enumeración. */
record ForgotPasswordResponse(String message) {

    static final ForgotPasswordResponse UNIFORM =
            new ForgotPasswordResponse("If the account is eligible, a password reset email will be sent.");
}
