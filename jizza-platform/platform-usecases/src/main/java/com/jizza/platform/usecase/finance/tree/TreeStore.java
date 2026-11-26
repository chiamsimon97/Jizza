package com.jizza.platform.usecase.finance.tree;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.finance.FinanceRecords;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Reads a goal's saved tree nodes ({@code fin_node}); the services decide what they mean. */
@Service
public class TreeStore {

  private final FinanceRecords records;

  public TreeStore(FinanceRecords records) {
    this.records = records;
  }

  public List<ObjectNode> rows(UUID owner, String planId) {
    return records.list("fin_node", owner, Map.of("planId", planId)).stream()
        .sorted(Comparator.comparingInt((ObjectNode n) -> n.path("sortOrder").asInt()).thenComparing(n -> n.path("name").asText()))
        .toList();
  }

  public static List<AssetTree.Node> nodes(List<ObjectNode> rows) {
    return rows.stream().map(TreeStore::node).toList();
  }

  public static AssetTree.Node node(ObjectNode r) {
    return new AssetTree.Node(r.path("id").asText(), r.hasNonNull("parentId") ? r.get("parentId").asText() : null,
        r.path("kind").asText(), r.path("nodeKey").asText(), r.path("name").asText(),
        r.hasNonNull("targetPct") ? r.get("targetPct").decimalValue() : null,
        r.hasNonNull("instrumentId") ? r.get("instrumentId").asText() : null);
  }

  public TreeIndex index(UUID owner, String planId) {
    return new TreeIndex(nodes(rows(owner, planId)));
  }
}
