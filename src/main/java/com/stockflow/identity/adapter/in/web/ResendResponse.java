package com.stockflow.identity.adapter.in.web;

/** Cuerpo idéntico exista o no la cuenta, para no permitir enumeración. */
record ResendResponse(String message) {

    static final ResendResponse UNIFORM =
            new ResendResponse("If the account is pending activation, a new activation email will be sent.");
}
