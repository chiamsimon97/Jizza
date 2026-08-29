package com.jizza.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The single deployable. One process runs the API and the worker together, which is what
 * local-first asks for; the {@code worker} profile drops the HTTP surface so additional worker
 * processes can be added later without a second artefact.
 */
@SpringBootApplication(scanBasePackages = "com.jizza")
public class JizzaApplication {

  public static void main(String[] args) {
    SpringApplication.run(JizzaApplication.class, args);
  }
}
