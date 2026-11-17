package com.financeflow.usecase;

import com.financeflow.model.User;

public record AuthResult(User user, Session session) {
}
