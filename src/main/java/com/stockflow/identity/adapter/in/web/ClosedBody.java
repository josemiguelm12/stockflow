package com.stockflow.identity.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cuerpo JSON administrativo leído tal cual, para poder rechazar cualquier propiedad no permitida (la configuración
 * global de Jackson ignora las desconocidas). Spring registra el objeto leído en DEBUG, así que su toString nunca
 * muestra nombres ni valores enviados por el cliente.
 */
final class ClosedBody {

    private final Map<String, Object> fields = new LinkedHashMap<>();

    @JsonAnySetter
    void put(String name, Object value) {
        fields.put(name, value);
    }

    Map<String, Object> fields() {
        return Collections.unmodifiableMap(fields);
    }

    @Override
    public String toString() {
        return "ClosedBody[" + fields.size() + " field(s)]";
    }
}
