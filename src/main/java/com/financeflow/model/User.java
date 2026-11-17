package com.financeflow.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

/** Usuário é o proprietário das categorias e transações. */
public record User(int id, String name, String email, @JsonIgnore String passwordHash) {

    public User withoutPassword() {
        return new User(id, name, email, "");
    }
}
