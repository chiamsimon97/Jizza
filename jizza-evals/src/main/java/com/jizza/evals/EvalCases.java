package com.jizza.evals;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Reads the scenario files under {@code evals/} on the classpath. */
public final class EvalCases {

  /** Strict, so a typo in a case file fails loudly instead of silently testing less. */
  static final ObjectMapper MAPPER = JsonMapper.builder()
      .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
      .findAndAddModules()
      .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
      .build();

  private EvalCases() {}

  public static List<EvalCase> all() {
    List<EvalCase> cases = new ArrayList<>();
    withScenarioFiles(files -> {
      for (Path file : files) {
        try (InputStream in = Files.newInputStream(file)) {
          cases.addAll(List.of(MAPPER.readValue(in, EvalCase[].class)));
        } catch (IOException e) {
          throw new IllegalStateException("Could not read " + file.getFileName() + ": "
              + e.getMessage(), e);
        }
      }
    });
    return cases;
  }

  /** The cases whose id contains the filter; all of them for a blank filter. */
  public static List<EvalCase> matching(String filter) {
    if (filter == null || filter.isBlank()) return all();
    return all().stream().filter(c -> c.id().contains(filter.strip())).toList();
  }

  /**
   * Hands over the scenario files, whether they sit in a directory (this module's own build) or
   * inside the jar (another module running the scenarios against its runtime).
   */
  static void withScenarioFiles(java.util.function.Consumer<List<Path>> reader) {
    try {
      var url = EvalCases.class.getClassLoader().getResource("evals");
      if (url == null) {
        reader.accept(List.of());
        return;
      }
      java.net.URI uri = url.toURI();
      if ("jar".equals(uri.getScheme())) {
        try (java.nio.file.FileSystem jar = java.nio.file.FileSystems.newFileSystem(uri, java.util.Map.of())) {
          reader.accept(list(jar.getPath("evals")));
        }
      } else {
        reader.accept(list(Path.of(uri)));
      }
    } catch (IOException | URISyntaxException e) {
      throw new IllegalStateException("Could not list the eval cases", e);
    }
  }

  private static List<Path> list(Path directory) throws IOException {
    try (Stream<Path> paths = Files.list(directory)) {
      return paths.filter(p -> p.toString().endsWith(".json"))
          .sorted(Comparator.comparing(Path::toString)).toList();
    }
  }
}
