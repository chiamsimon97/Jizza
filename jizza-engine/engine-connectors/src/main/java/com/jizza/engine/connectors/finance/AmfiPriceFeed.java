package com.jizza.engine.connectors.finance;

import com.jizza.engine.api.finance.PriceFeed;
import com.jizza.engine.connectors.http.GuardedHttpClient;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * AMFI's public daily NAV file, read through {@link GuardedHttpClient} (the platform's only way
 * out). The file is plain text: header lines, fund-house names, and one
 * {@code code;isin;isin;name;nav;date} line per scheme. A line that is not a scheme is skipped.
 */
@Component
public class AmfiPriceFeed implements PriceFeed {

  static final URI NAV_ALL = URI.create("https://www.amfiindia.com/spages/NAVAll.txt");
  /** The whole file is a few megabytes; a little headroom, and still a cap. */
  static final long MAX_BYTES = 24L * 1024 * 1024;
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

  private final GuardedHttpClient http;

  public AmfiPriceFeed(GuardedHttpClient http) {
    this.http = http;
  }

  @Override
  public List<Nav> amfiNavs() {
    GuardedHttpClient.Response r = http.send("GET", NAV_ALL, Map.of("Accept", "text/plain"), null,
        "amfiindia.com", MAX_BYTES);
    if (!r.ok()) throw new IllegalStateException("AMFI answered " + r.status());
    return parse(r.body());
  }

  static List<Nav> parse(String body) {
    List<Nav> out = new ArrayList<>();
    if (body == null) return out;
    for (String line : body.split("\\r?\\n")) {
      String[] p = line.split(";", -1);
      if (p.length < 6 || !p[0].trim().matches("\\d{3,8}")) continue;
      BigDecimal nav;
      LocalDate date;
      try {
        nav = new BigDecimal(p[4].trim());
        date = LocalDate.parse(p[5].trim(), DATE);
      } catch (NumberFormatException | DateTimeParseException e) {
        continue;
      }
      if (nav.signum() <= 0) continue;
      out.add(new Nav(p[0].trim(), isin(p[1]), isin(p[2]), p[3].trim(), nav, date));
    }
    return out;
  }

  private static String isin(String s) {
    String v = s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    return v.matches("[A-Z]{2}[A-Z0-9]{9}[0-9]") ? v : null;
  }
}
