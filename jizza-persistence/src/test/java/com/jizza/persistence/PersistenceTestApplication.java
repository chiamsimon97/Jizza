package com.jizza.persistence;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Minimal Boot configuration for the database-backed slice tests. The real application class lives
 * in {@code jizza-app}, which this module must not depend on.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class PersistenceTestApplication {}
