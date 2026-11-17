package com.financeflow.model;

public enum DomainError {
    NOT_FOUND("registro não encontrado"),
    CONFLICT("registro já cadastrado"),
    INVALID_CREDENTIALS("e-mail ou senha inválidos"),
    INVALID_CATEGORY("categoria inválida para este usuário"),
    INVALID_INPUT("dados inválidos"),
    GOOGLE_EMAIL_MISMATCH("use uma conta Google com o mesmo e-mail da sua conta FinanceFlow"),
    GOOGLE_ACCOUNT_EXISTS("já existe uma conta com este e-mail; entre com sua senha para conectar o Google"),
    GOOGLE_ACCOUNT_CONFLICT("esta conta Google já está conectada a outra conta, ou o usuário já conectou outra conta Google");

    private final String message;

    DomainError(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
