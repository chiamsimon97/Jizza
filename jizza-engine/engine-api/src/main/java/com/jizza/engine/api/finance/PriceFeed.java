package com.jizza.engine.api.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Where Kai reads public prices from (Phase 2). Every call goes out through the platform's guarded
 * HTTP client; nothing about the user is sent.
 */
public interface PriceFeed {

  /** One mutual fund scheme's NAV for a date, as AMFI publishes it. */
  record Nav(String amfiCode, String isinGrowth, String isinReinvest, String schemeName,
             BigDecimal nav, LocalDate date) {}

  /** Today's NAV file of every scheme from AMFI. */
  List<Nav> amfiNavs();
}
