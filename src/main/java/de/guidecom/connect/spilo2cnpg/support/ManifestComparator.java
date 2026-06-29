package de.guidecom.connect.spilo2cnpg.support;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.core.JsonParser;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Structural comparator for Kubernetes manifest sets (multi-document YAML).
 *
 * <p>Loads both sides into generic maps, indexes the resources by {@code kind/namespace/name}, and
 * produces a deep, path-based diff per resource. Scalars are compared by their string form so that
 * cosmetic YAML quoting differences (e.g. {@code "5432"} vs {@code 5432}) are treated as equal.
 *
 * <p>Useful for verifying that the generated CNPG manifest set matches a reference (e.g. the
 * deployed pilot), independent of key ordering or comments.
 */
public final class ManifestComparator {

  private final YAMLMapper mapper = new YAMLMapper();

  public enum Presence {BOTH, ONLY_LEFT, ONLY_RIGHT}

  /** A single field-level difference between two resources. */
  public record Difference(Type type, String path, String left, String right) {
    public enum Type {CHANGED, ONLY_LEFT, ONLY_RIGHT}
  }

  /** Identity of a resource within a manifest set. */
  public record ResourceKey(String kind, String namespace, String name) {
    @Override
    public String toString() {
      String ns = namespace == null ? "" : namespace + "/";
      return (kind == null ? "?" : kind) + "/" + ns + (name == null ? "?" : name);
    }
  }

  /** Comparison outcome for one resource. */
  public record ResourceDiff(ResourceKey key, Presence presence, List<Difference> differences) {
    public boolean identical() {
      return presence == Presence.BOTH && differences.isEmpty();
    }
  }

  /** Overall comparison outcome. */
  public record ComparisonResult(List<ResourceDiff> resources) {
    public boolean identical() {
      return resources.stream().allMatch(ResourceDiff::identical);
    }

    public int totalDifferences() {
      return resources.stream()
          .mapToInt(r -> r.presence() == Presence.BOTH ? r.differences().size() : 1)
          .sum();
    }
  }

  // -------------------------------------------------------------------------
  // Parsing
  // -------------------------------------------------------------------------

  public List<Map<String, Object>> parseFile(File file) throws IOException {
    return parse(new String(java.nio.file.Files.readAllBytes(file.toPath())));
  }

  @SuppressWarnings("unchecked")
  public List<Map<String, Object>> parse(String yaml) throws IOException {
    List<Map<String, Object>> docs = new ArrayList<>();
    YAMLFactory factory = new YAMLFactory();
    // Read as Object so that empty documents (a bare "---") deserialize to null instead of
    // failing; only non-empty mapping documents are kept.
    try (JsonParser parser = factory.createParser(yaml)) {
      MappingIterator<Object> it = mapper.readValues(parser,
          mapper.getTypeFactory().constructType(Object.class));
      while (it.hasNext()) {
        Object doc = it.next();
        if (doc instanceof Map<?, ?> map && !map.isEmpty()) {
          docs.add((Map<String, Object>) map);
        }
      }
    }
    return docs;
  }

  // -------------------------------------------------------------------------
  // Comparison
  // -------------------------------------------------------------------------

  public ComparisonResult compare(List<Map<String, Object>> left, List<Map<String, Object>> right) {
    Map<ResourceKey, Map<String, Object>> leftByKey = indexByKey(left);
    Map<ResourceKey, Map<String, Object>> rightByKey = indexByKey(right);

    Set<ResourceKey> keys = new LinkedHashSet<>();
    keys.addAll(leftByKey.keySet());
    keys.addAll(rightByKey.keySet());

    List<ResourceDiff> results = new ArrayList<>();
    for (ResourceKey key : keys) {
      Map<String, Object> l = leftByKey.get(key);
      Map<String, Object> r = rightByKey.get(key);
      if (l == null) {
        results.add(new ResourceDiff(key, Presence.ONLY_RIGHT, List.of()));
      } else if (r == null) {
        results.add(new ResourceDiff(key, Presence.ONLY_LEFT, List.of()));
      } else {
        List<Difference> diffs = new ArrayList<>();
        diff("", l, r, diffs);
        results.add(new ResourceDiff(key, Presence.BOTH, diffs));
      }
    }
    results.sort((a, b) -> a.key().toString().compareTo(b.key().toString()));
    return new ComparisonResult(results);
  }

  private Map<ResourceKey, Map<String, Object>> indexByKey(List<Map<String, Object>> docs) {
    Map<ResourceKey, Map<String, Object>> byKey = new LinkedHashMap<>();
    for (Map<String, Object> doc : docs) {
      byKey.put(keyOf(doc), doc);
    }
    return byKey;
  }

  @SuppressWarnings("unchecked")
  private ResourceKey keyOf(Map<String, Object> doc) {
    String kind = str(doc.get("kind"));
    String namespace = null;
    String name = null;
    Object metadata = doc.get("metadata");
    if (metadata instanceof Map<?, ?> m) {
      namespace = str(((Map<String, Object>) m).get("namespace"));
      name = str(((Map<String, Object>) m).get("name"));
    }
    return new ResourceKey(kind, namespace, name);
  }

  @SuppressWarnings("unchecked")
  private void diff(String path, Object left, Object right, List<Difference> out) {
    if (left instanceof Map && right instanceof Map) {
      Map<String, Object> l = (Map<String, Object>) left;
      Map<String, Object> r = (Map<String, Object>) right;
      Set<String> keys = new LinkedHashSet<>();
      keys.addAll(l.keySet());
      keys.addAll(r.keySet());
      for (String k : keys) {
        String child = path + "/" + k;
        if (!r.containsKey(k)) {
          out.add(new Difference(Difference.Type.ONLY_LEFT, child, str(l.get(k)), null));
        } else if (!l.containsKey(k)) {
          out.add(new Difference(Difference.Type.ONLY_RIGHT, child, null, str(r.get(k))));
        } else {
          diff(child, l.get(k), r.get(k), out);
        }
      }
    } else if (left instanceof List && right instanceof List) {
      List<Object> l = (List<Object>) left;
      List<Object> r = (List<Object>) right;
      int min = Math.min(l.size(), r.size());
      for (int i = 0; i < min; i++) {
        diff(path + "[" + i + "]", l.get(i), r.get(i), out);
      }
      for (int i = min; i < l.size(); i++) {
        out.add(new Difference(Difference.Type.ONLY_LEFT, path + "[" + i + "]", str(l.get(i)), null));
      }
      for (int i = min; i < r.size(); i++) {
        out.add(new Difference(Difference.Type.ONLY_RIGHT, path + "[" + i + "]", null, str(r.get(i))));
      }
    } else {
      if (!str(left).equals(str(right))) {
        out.add(new Difference(Difference.Type.CHANGED, path, str(left), str(right)));
      }
    }
  }

  private static String str(Object o) {
    return String.valueOf(o);
  }
}
