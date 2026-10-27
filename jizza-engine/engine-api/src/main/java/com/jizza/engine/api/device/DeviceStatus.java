package com.jizza.engine.api.device;

/**
 * Where a paired device stands. A disabled device keeps its pairing but is sent nothing; a revoked
 * one can never sign in again.
 */
public enum DeviceStatus { ACTIVE, DISABLED, REVOKED }
