package com.financeflow.usecase;

import com.financeflow.model.DomainError;
import com.financeflow.model.DomainException;
import com.financeflow.model.User;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

class MemoryAuthStore implements AuthStore {

    record StoredSession(int userId, Instant expiresAt) {
    }

    final Map<String, User> users = new HashMap<>();
    final Map<String, StoredSession> sessions = new HashMap<>();

    @Override
    public User createUser(User user) {
        if (users.containsKey(user.email())) {
            throw new DomainException(DomainError.CONFLICT);
        }
        User saved = new User(users.size() + 1, user.name(), user.email(), user.passwordHash());
        users.put(user.email(), saved);
        return saved;
    }

    @Override
    public User getUserByEmail(String email) {
        User user = users.get(email);
        if (user == null) {
            throw new DomainException(DomainError.INVALID_CREDENTIALS);
        }
        return user;
    }

    @Override
    public void createSession(String tokenHash, int userId, Instant expiresAt) {
        sessions.put(tokenHash, new StoredSession(userId, expiresAt));
    }

    @Override
    public User getUserBySession(String tokenHash) {
        StoredSession session = sessions.get(tokenHash);
        if (session == null || !session.expiresAt().isAfter(Instant.now())) {
            throw new DomainException(DomainError.INVALID_CREDENTIALS);
        }
        return users.values().stream().filter(user -> user.id() == session.userId()).findFirst()
                .map(User::withoutPassword)
                .orElseThrow(() -> new DomainException(DomainError.INVALID_CREDENTIALS));
    }

    @Override
    public void deleteSession(String tokenHash) {
        sessions.remove(tokenHash);
    }
}
