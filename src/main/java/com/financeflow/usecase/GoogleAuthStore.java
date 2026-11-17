package com.financeflow.usecase;

import com.financeflow.model.GoogleIdentity;
import com.financeflow.model.User;

/**
 * Resolve identidades Google verificadas sem vincular automaticamente uma conta
 * com senha apenas porque o e-mail coincide.
 */
public interface GoogleAuthStore {

    User resolveGoogleUser(GoogleIdentity identity);

    void linkGoogleUser(int userId, GoogleIdentity identity);
}
