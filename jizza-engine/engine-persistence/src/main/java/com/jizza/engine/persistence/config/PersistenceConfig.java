package com.jizza.engine.persistence.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Owns the JPA wiring for the whole monolith: every module's data access goes through the entities
 * and repositories declared here, so no other module needs its own persistence configuration.
 */
@Configuration
@EnableTransactionManagement
// The converter package is scanned alongside the entities: an autoApply converter is only
// registered with the persistence unit if entity scanning finds it, and without that Hibernate
// would silently fall back to storing enums as ordinals rather than the Contract's CHECK vocabulary.
@EntityScan(basePackages = {"com.jizza.engine.persistence.entity", "com.jizza.engine.persistence.converter"})
@EnableJpaRepositories(basePackages = "com.jizza.engine.persistence.repository")
public class PersistenceConfig {}
