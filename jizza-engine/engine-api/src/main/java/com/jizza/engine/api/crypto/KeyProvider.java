package com.jizza.engine.api.crypto;

import javax.crypto.SecretKey;

/** Source of the master key that wraps data keys. Local keystore now, KMS later (R7). */
public interface KeyProvider {

  SecretKey masterKey();

  /** Identifier of the active master key, stored alongside ciphertext to support rotation. */
  String activeKeyId();

  /** Resolve a previous master key by id so ciphertext written before a rotation still decrypts. */
  SecretKey keyById(String keyId);
}
