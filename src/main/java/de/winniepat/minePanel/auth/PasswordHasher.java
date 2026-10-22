package de.winniepat.minePanel.auth;

import org.mindrot.jbcrypt.BCrypt;

public final class PasswordHasher {

    public String hash(String rawPassword) {
        return BCrypt.hashpw(rawPassword, BCrypt.gensalt(12));
    }

    public boolean verify(String rawPassword, String hashedPassword) {
        return BCrypt.checkpw(rawPassword, hashedPassword);
    }
}

