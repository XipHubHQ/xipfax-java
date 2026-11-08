package com.xiphub.xipfax.sdk;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** A request the stand-in was given, its headers and its body as they were sent. */
final class SentRequest {
  private final String method;
  private final URI uri;
  private final Map<String, List<String>> headers;
  private final byte[] body;

  SentRequest(String method, URI uri, Map<String, List<String>> headers, byte[] body) {
    this.method = method;
    this.uri = uri;
    Map<String, List<String>> kept = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    for (Map.Entry<String, List<String>> header : headers.entrySet()) {
      kept.computeIfAbsent(header.getKey(), name -> new ArrayList<>()).addAll(header.getValue());
    }
    this.headers = kept;
    this.body = body.clone();
  }

  /** A request of method to address, with headers and body as they are given. */
  static SentRequest of(String method, String address, Map<String, List<String>> headers, String body) {
    return new SentRequest(method, URI.create(address), headers, body.getBytes(StandardCharsets.UTF_8));
  }

  String method() {
    return method;
  }

  URI uri() {
    return uri;
  }

  byte[] body() {
    return body.clone();
  }

  /** The values a header was sent with, in their order: none where it wasn't sent. */
  List<String> values(String name) {
    List<String> values = headers.get(name);
    return values == null ? Collections.emptyList() : Collections.unmodifiableList(values);
  }
}
