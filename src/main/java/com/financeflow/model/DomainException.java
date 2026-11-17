package com.financeflow.model;

/** Erro reconhecível pelas camadas superiores, equivalente aos erros sentinela. */
public class DomainException extends RuntimeException {

    private final DomainError error;

    public DomainException(DomainError error) {
        super(error.message());
        this.error = error;
    }

    public DomainException(DomainError error, String detail) {
        super(error.message() + ": " + detail);
        this.error = error;
    }

    public DomainError error() {
        return error;
    }

    public boolean is(DomainError expected) {
        return error == expected;
    }
}
