package com.jizza.platform.usecase.finance.tree;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a holding sits in a goal's tree: its own instrument node if it has one, else the
 * sub-category its instrument belongs to, else its asset class, else nowhere ("not in this goal's
 * plan"). Pure.
 */
public final class TreeIndex {

  private final Map<String, AssetTree.Node> byId = new HashMap<>();
  private final Map<String, AssetTree.Node> byInstrument = new HashMap<>();
  private final Map<String, AssetTree.Node> classByKey = new HashMap<>();
  private final Map<String, List<AssetTree.Node>> subsByKey = new HashMap<>();

  public TreeIndex(List<AssetTree.Node> nodes) {
    for (AssetTree.Node n : nodes) {
      byId.put(n.id(), n);
      switch (n.kind()) {
        case "instrument" -> byInstrument.put(n.instrumentId(), n);
        case "asset_class" -> classByKey.put(n.key(), n);
        default -> subsByKey.computeIfAbsent(n.key(), k -> new ArrayList<>()).add(n);
      }
    }
  }

  public AssetTree.Node node(String id) {
    return byId.get(id);
  }

  /** The node a holding of this instrument is counted at, or null when off the tree. */
  public AssetTree.Node place(String instrumentId, String assetClass, String subCategory) {
    AssetTree.Node own = instrumentId == null ? null : byInstrument.get(instrumentId);
    if (own != null) return own;
    AssetTree.Node cls = assetClass == null ? null : classByKey.get(assetClass);
    List<AssetTree.Node> subs = subCategory == null ? List.of() : subsByKey.getOrDefault(subCategory, List.of());
    for (AssetTree.Node s : subs) {
      if (cls != null && cls.id().equals(s.parentId())) return s;
    }
    if (!subs.isEmpty() && cls == null) return subs.get(0);
    return cls;
  }

  public AssetTree.Node place(JsonNode instrument) {
    return place(instrument.path("id").asText(null), instrument.path("assetClass").asText(null),
        instrument.path("subCategory").asText(null));
  }

  /** The node and its ancestors, nearest first. */
  public List<AssetTree.Node> path(AssetTree.Node n) {
    List<AssetTree.Node> out = new ArrayList<>();
    AssetTree.Node at = n;
    int guard = 0;
    while (at != null && guard++ < 16) {
      out.add(at);
      at = at.parentId() == null ? null : byId.get(at.parentId());
    }
    return out;
  }

  /** Whether {@code n} is {@code ancestor} or lies under it. */
  public boolean under(AssetTree.Node n, String ancestorId) {
    for (AssetTree.Node p : path(n)) if (p.id().equals(ancestorId)) return true;
    return false;
  }
}
