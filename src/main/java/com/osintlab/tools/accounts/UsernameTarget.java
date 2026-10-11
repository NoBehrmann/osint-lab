package com.osintlab.tools.accounts;

import com.osintlab.support.Inputs;

/**
 * Username tools also accept an email address and search for its local part
 * (john.doe84@gmail.com -> john.doe84), since people often reuse it as a handle.
 */
record UsernameTarget(String username, String fromEmail) {

    static UsernameTarget of(String raw) {
        String s = Inputs.require(raw, "username");
        if (s.contains("@") && !s.startsWith("@")) {
            String email = Inputs.email(s);
            return new UsernameTarget(Inputs.username(email.substring(0, email.indexOf('@'))), email);
        }
        return new UsernameTarget(Inputs.username(s), null);
    }
}
