package com.financeflow.model;

/**
 * Contém somente claims já verificadas pela camada OpenID Connect.
 * O subject é o identificador estável da conta Google; o e-mail não é.
 */
public record GoogleIdentity(String subject, String email, String name) {
}
