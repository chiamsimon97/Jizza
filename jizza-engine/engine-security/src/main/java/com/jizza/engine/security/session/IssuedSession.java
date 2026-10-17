package com.jizza.engine.security.session;

import com.jizza.engine.persistence.entity.SessionEntity;

/** A newly created session and the cookie token for it, which exists nowhere else. */
public record IssuedSession(SessionEntity session, String token) {}
