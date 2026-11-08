package com.xiphub.xipfax.sdk;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

/** Plain values as the contract's refusals give them. */
final class Text {
  private Text() {}

  /**
   * A value as its refusals name it: a string as it is, a number at its shortest, a list as [a b],
   * an object as map[name:value], null as &lt;nil&gt;.
   */
  static String of(Object value) {
    if (value == null) {
      return "<nil>";
    }
    if (value instanceof Double) {
      double number = (Double) value;
      if (number == Math.rint(number) && Math.abs(number) < 1e15) {
        return Long.toString((long) number);
      }
      return Double.toString(number);
    }
    if (value instanceof List) {
      StringJoiner items = new StringJoiner(" ", "[", "]");
      for (Object item : (List<?>) value) {
        items.add(of(item));
      }
      return items.toString();
    }
    if (value instanceof Map) {
      StringJoiner members = new StringJoiner(" ", "map[", "]");
      for (Map.Entry<?, ?> member : new TreeMap<>((Map<?, ?>) value).entrySet()) {
        members.add(member.getKey() + ":" + of(member.getValue()));
      }
      return members.toString();
    }
    return String.valueOf(value);
  }

  /** A string in double quotes, its quotes, backslashes and control characters escaped. */
  static String quote(String text) {
    StringBuilder quoted = new StringBuilder("\"");
    for (int index = 0; index < text.length(); index++) {
      char character = text.charAt(index);
      switch (character) {
        case '"':
          quoted.append("\\\"");
          break;
        case '\\':
          quoted.append("\\\\");
          break;
        case '\n':
          quoted.append("\\n");
          break;
        case '\r':
          quoted.append("\\r");
          break;
        case '\t':
          quoted.append("\\t");
          break;
        default:
          if (Character.isISOControl(character)) {
            quoted.append(String.format("\\u%04x", (int) character));
          } else {
            quoted.append(character);
          }
          break;
      }
    }
    return quoted.append('"').toString();
  }

  /** Strings each in double quotes, as ["a" "b"]. */
  static String quoteAll(List<String> texts) {
    StringJoiner quoted = new StringJoiner(" ", "[", "]");
    for (String text : texts) {
      quoted.add(quote(text));
    }
    return quoted.toString();
  }
}
