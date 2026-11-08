package com.xiphub.xipfax.sdk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/** The API's answers the specs are given, in JSON, each as the document gives it. */
final class Answers {
  private static final ObjectMapper WRITER = new ObjectMapper();

  /** GET /quote's answer for a fax of one page. */
  static final String QUOTE =
      "{\"pages\":1,\"price\":{\"amount_minor\":100,\"currency\":\"USD\"},\"price_shown\":\"$1.00\","
          + "\"terms\":\"The stand-in’s terms.\",\"price_list\":\"stand-in\"}";

  /** An office of GET /offices, which takes Form 2848 from Ohio. */
  static final String OFFICE =
      "{\"id\":\"stand-in-office\",\"name\":\"A stand-in office\",\"fax_number\":\"+12025550143\","
          + "\"fax_number_from_abroad\":null,\"applies_to\":[\"Ohio\"],\"takes\":[\"Form 2848\"],"
          + "\"signed_in_ink\":false,\"confidential_cover\":false,"
          + "\"sources\":[{\"title\":\"A stand-in page\",\"url\":\"https://example.com/office\"}],"
          + "\"checked_on\":\"2026-11-01\"}";

  /** GET /plan's answer for a client with no plan. */
  static final String NO_PLAN = "{\"plan\":null}";

  private Answers() {}

  /** A document of the draft, ready, by its id and its name. */
  static String document(String id, String name) {
    Map<String, Object> document = new HashMap<>();
    document.put("id", id);
    document.put("name", name);
    document.put("size_bytes", 20480);
    document.put("page_count", 2);
    document.put("status", "ready");
    document.put("url", "https://example.com/" + id + ".pdf");
    return objectOf(document);
  }

  /** fax_1, a fax you sent that was delivered. */
  static String fax() {
    return fax(Collections.emptyMap());
  }

  /** fax_1, a fax you sent that was delivered, with each member changes names in place of its own. */
  static String fax(Map<String, Object> changes) {
    Map<String, Object> fax = new HashMap<>();
    fax.put("id", "fax_1");
    fax.put("direction", "sent");
    fax.put("status", "delivered");
    fax.put("step", null);
    fax.put("office_id", null);
    fax.put("to_number", "+12025550143");
    fax.put("from_number", "+12025550100");
    fax.put("pages", 2);
    fax.put("price", null);
    fax.put("sent_at", "2026-11-07T02:00:00Z");
    fax.put("finished_at", "2026-11-07T02:03:00Z");
    fax.put("retry_at", null);
    fax.put("failure", null);
    fax.put("time_zone", "America/New_York");
    fax.put("sender_name", null);
    fax.put("cover_confidential", false);
    fax.putAll(changes);
    return objectOf(fax);
  }

  /** The API's refusal, by its code and its sentence. */
  static String refusal(String code, String message) {
    Map<String, Object> error = new HashMap<>();
    error.put("code", code);
    error.put("message", message);
    error.put("details", Collections.emptyList());
    error.put("request_id", "req_1");
    error.put("doc_url", null);
    return objectOf(Collections.<String, Object>singletonMap("error", error));
  }

  /** Members by name and value, in pairs, a null value among them: "step", null. */
  static Map<String, Object> members(Object... namesAndValues) {
    if (namesAndValues.length % 2 != 0) {
      throw new IllegalArgumentException("members come in pairs, a name and its value");
    }
    Map<String, Object> members = new HashMap<>();
    for (int at = 0; at < namesAndValues.length; at += 2) {
      members.put((String) namesAndValues[at], namesAndValues[at + 1]);
    }
    return members;
  }

  /** Members as a JSON object, its members, and its objects' members, in the order of their names. */
  private static String objectOf(Map<String, Object> members) {
    try {
      return WRITER.writeValueAsString(sorted(members));
    } catch (JsonProcessingException unwritable) {
      throw new IllegalArgumentException("the answer can't be written as JSON", unwritable);
    }
  }

  private static Map<String, Object> sorted(Map<String, Object> members) {
    Map<String, Object> sorted = new TreeMap<>();
    for (Map.Entry<String, Object> member : members.entrySet()) {
      Object value = member.getValue();
      if (value instanceof Map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> inner = (Map<String, Object>) value;
        value = sorted(inner);
      }
      sorted.put(member.getKey(), value);
    }
    return sorted;
  }
}
