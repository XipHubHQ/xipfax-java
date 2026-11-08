package com.xiphub.xipfax.sdk;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** JSON as the contract reads it, in plain values. */
final class Json {
  /** A reader of one JSON value, which refuses anything after it. */
  private static final ObjectMapper READER =
      new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private Json() {}

  /**
   * JSON as plain values: an object as a Map of its members, in the order of their names, the last
   * of a name kept; an array as a List; a number as a Double; a String, a Boolean, and null for null.
   *
   * @throws IOException where the bytes are no JSON, or hold nothing
   */
  static Object parse(byte[] utf8) throws IOException {
    JsonNode root = READER.readTree(utf8);
    if (root == null || root.isMissingNode()) {
      throw new IOException("no JSON value");
    }
    return plain(root);
  }

  /** The value of an object's member, or null where it has none or isn't an object. */
  static Object get(Object members, String name) {
    return members instanceof Map ? ((Map<?, ?>) members).get(name) : null;
  }

  /** An object's members, or null where the value is no object. */
  @SuppressWarnings("unchecked")
  static Map<String, Object> object(Object value) {
    return value instanceof Map ? (Map<String, Object>) value : null;
  }

  /** An array's items, or null where the value is no array. */
  @SuppressWarnings("unchecked")
  static List<Object> array(Object value) {
    return value instanceof List ? (List<Object>) value : null;
  }

  /** Whether two plain values are the same JSON. */
  static boolean same(Object first, Object second) {
    if (first == null || second == null) {
      return first == second;
    }
    if (first instanceof List && second instanceof List) {
      List<?> a = (List<?>) first;
      List<?> b = (List<?>) second;
      if (a.size() != b.size()) {
        return false;
      }
      for (int index = 0; index < a.size(); index++) {
        if (!same(a.get(index), b.get(index))) {
          return false;
        }
      }
      return true;
    }
    if (first instanceof Map && second instanceof Map) {
      Map<?, ?> a = (Map<?, ?>) first;
      Map<?, ?> b = (Map<?, ?>) second;
      if (a.size() != b.size()) {
        return false;
      }
      for (Map.Entry<?, ?> member : a.entrySet()) {
        if (!b.containsKey(member.getKey()) || !same(member.getValue(), b.get(member.getKey()))) {
          return false;
        }
      }
      return true;
    }
    if (first instanceof String || first instanceof Boolean || first instanceof Double) {
      return first.equals(second);
    }
    return false;
  }

  private static Object plain(JsonNode node) {
    switch (node.getNodeType()) {
      case OBJECT:
        {
          Map<String, Object> members = new TreeMap<>();
          for (Map.Entry<String, JsonNode> member : node.properties()) {
            members.put(member.getKey(), plain(member.getValue()));
          }
          return members;
        }
      case ARRAY:
        {
          List<Object> items = new ArrayList<>();
          for (JsonNode item : node) {
            items.add(plain(item));
          }
          return items;
        }
      case STRING:
        return node.textValue();
      case NUMBER:
        return node.doubleValue();
      case BOOLEAN:
        return node.booleanValue();
      case NULL:
        return null;
      default:
        throw new IllegalArgumentException("JSON holds no " + node.getNodeType() + " value");
    }
  }
}
