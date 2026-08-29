package com.jizza.persistence.config;

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
@EntityScan(basePackages = "com.jizza.persistence.entity")
@EnableJpaRepositories(basePackages = "com.jizza.persistence.repository")
public class PersistenceConfig {}
