package com.financeflow.usecase;

import com.financeflow.model.User;
import java.time.Instant;

public interface AuthStore {

    User createUser(User user);

    User getUserByEmail(String email);

    void createSession(String tokenHash, int userId, Instant expiresAt);

    User getUserBySession(String tokenHash);

    void deleteSession(String tokenHash);
}
