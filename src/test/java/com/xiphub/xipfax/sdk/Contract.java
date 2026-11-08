package com.xiphub.xipfax.sdk;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The API's document, openapi.json, as the repository holds it, which reads what the SDK sends and
 * is answered against it: the operation a request is, and each thing in it the document refuses,
 * from its address, its key and its version to each parameter and its body; and what in an answer or
 * an event's body the document's schema refuses. It reads the schema keywords the document uses,
 * and refuses a schema with one it doesn't read, so that a document that comes to say more than this
 * reads fails the specs rather than passing unread. Every value it reads is JSON as {@link
 * Json#parse} reads it.
 */
final class Contract {
  /** A document the contract can't read: one that names no server, or a reference to nothing. */
  static final class Unreadable extends RuntimeException {
    private static final long serialVersionUID = 1L;

    Unreadable(String message) {
      super(message);
    }

    Unreadable(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** One operation of the document. */
  static final class Operation {
    /** Its operationId. */
    final String id;

    /** Its method, GET. */
    final String method;

    /** Its path, /faxes/{fax_id}. */
    final String path;

    /** The first status of 2xx it answers. */
    final int success;

    final List<Parameter> parameters;
    final Map<String, Object> body;
    final Pattern pattern;
    final List<String> names;
    final Map<String, Object> responses;

    Operation(
        String id,
        String method,
        String path,
        int success,
        List<Parameter> parameters,
        Map<String, Object> body,
        Pattern pattern,
        List<String> names,
        Map<String, Object> responses) {
      this.id = id;
      this.method = method;
      this.path = path;
      this.success = success;
      this.parameters = Collections.unmodifiableList(parameters);
      this.body = body;
      this.pattern = pattern;
      this.names = Collections.unmodifiableList(names);
      this.responses = responses;
    }
  }

  /** One parameter of an operation. */
  static final class Parameter {
    final String name;
    final String in;
    final boolean required;
    final Map<String, Object> schema;

    Parameter(String name, String in, boolean required, Map<String, Object> schema) {
      this.name = name;
      this.in = in;
      this.required = required;
      this.schema = schema;
    }
  }

  /** What a request is, read against the document: its operation's id, and what it refuses. */
  static final class Reading {
    final String id;
    final List<String> refused;

    Reading(String id, List<String> refused) {
      this.id = id;
      this.refused = Collections.unmodifiableList(refused);
    }
  }

  private static final List<String> METHODS =
      Arrays.asList("get", "put", "post", "delete", "options", "head", "patch", "trace");

  private static final Pattern BEARER = Pattern.compile("\\ABearer \\S+\\z");

  /** A parameter's place in a path, {fax_id}. */
  private static final Pattern PATH_PARAMETER = Pattern.compile("\\{([^}]+)\\}");

  /** The keywords that describe a value without constraining it. */
  private static final Set<String> ANNOTATIONS =
      new HashSet<>(Arrays.asList("description", "title", "default", "examples"));

  private final Map<String, Object> raw;
  private final URI server;
  private final String version;
  private final List<Operation> operations;
  private final Map<String, Map<String, Object>> webhooks;

  private Contract(String path, Map<String, Object> raw) {
    this.raw = raw;
    Object info = Json.get(raw, "info");
    Object stated = Json.get(info, "version");
    version = stated instanceof String ? (String) stated : "";

    List<Object> servers = Json.array(Json.get(raw, "servers"));
    if (servers == null || servers.isEmpty()) {
      throw new Unreadable(path + " names no server");
    }
    Object address = Json.get(servers.get(0), "url");
    try {
      server = new URI(String.valueOf(address));
    } catch (URISyntaxException notOne) {
      throw new Unreadable(path + "'s server, " + address + ", is no address", notOne);
    }
    if (!server.isAbsolute()) {
      throw new Unreadable(path + "'s server, " + address + ", is no address");
    }

    List<Operation> found = new ArrayList<>();
    Map<String, Object> paths = Json.object(Json.get(raw, "paths"));
    if (paths != null) {
      for (String route : sorted(paths.keySet())) {
        Map<String, Object> item = Json.object(paths.get(route));
        for (String method : METHODS) {
          Map<String, Object> entry = Json.object(Json.get(item, method));
          if (entry != null) {
            found.add(operationOf(route, method, item, entry));
          }
        }
      }
    }
    operations = Collections.unmodifiableList(found);

    Map<String, Map<String, Object>> events = new TreeMap<>();
    Map<String, Object> posted = Json.object(Json.get(raw, "webhooks"));
    if (posted != null) {
      for (String name : sorted(posted.keySet())) {
        Map<String, Object> post = Json.object(Json.get(posted.get(name), "post"));
        Map<String, Object> schema;
        try {
          schema = bodySchema(post);
        } catch (Unreadable e) {
          throw new Unreadable(
              "the webhook " + name + " posts no JSON body the document gives: " + e.getMessage(), e);
        }
        if (schema == null) {
          throw new Unreadable("the webhook " + name + " posts no JSON body the document gives");
        }
        events.put(name, schema);
      }
    }
    webhooks = Collections.unmodifiableMap(events);
  }

  /** The document's info.version, the version of the API it is. */
  String version() {
    return version;
  }

  /** The document's operations, by their paths in order and the method within each. */
  List<Operation> operations() {
    return operations;
  }

  /** The schema of the body each event posts, by the event's name. */
  Map<String, Map<String, Object>> webhooks() {
    return webhooks;
  }

  /** The document as the repository holds it, at its root, where Maven runs the specs. */
  static Contract load() {
    return load(Paths.get("openapi.json"));
  }

  /** The document at path. */
  static Contract load(Path path) {
    Object value;
    try {
      value = Json.parse(Files.readAllBytes(path));
    } catch (IOException unread) {
      throw new UncheckedIOException(path + ": " + unread.getMessage(), unread);
    }
    Map<String, Object> raw = Json.object(value);
    if (raw == null) {
      throw new Unreadable(path + " is no JSON object");
    }
    return new Contract(path.toString(), raw);
  }

  private Operation operationOf(
      String route, String method, Map<String, Object> item, Map<String, Object> entry) {
    Object named = Json.get(entry, "operationId");
    String id = named instanceof String ? (String) named : "";

    // The operation's parameters, the path item's and then its own, one of its own taking the
    // place of the item's of the same name and place.
    Map<String, Parameter> declared = new LinkedHashMap<>();
    for (Map<String, Object> owner : Arrays.asList(item, entry)) {
      List<Object> list = Json.array(Json.get(owner, "parameters"));
      if (list == null) {
        continue;
      }
      for (Object value : list) {
        Map<String, Object> found;
        try {
          found = resolve(value);
        } catch (Unreadable e) {
          throw new Unreadable(id + ": " + e.getMessage(), e);
        }
        Object name = Json.get(found, "name");
        Object in = Json.get(found, "in");
        Parameter parameter =
            new Parameter(
                name instanceof String ? (String) name : "",
                in instanceof String ? (String) in : "",
                Boolean.TRUE.equals(Json.get(found, "required")),
                Json.object(Json.get(found, "schema")));
        // The place of the item's parameter is kept where one of the operation's replaces it.
        declared.put(parameter.in + ":" + parameter.name.toLowerCase(Locale.ROOT), parameter);
      }
    }

    Map<String, Object> body;
    try {
      body = bodySchema(entry);
    } catch (Unreadable e) {
      throw new Unreadable(id + ": " + e.getMessage(), e);
    }

    Map<String, Object> responses = Json.object(Json.get(entry, "responses"));
    if (responses == null) {
      responses = Collections.emptyMap();
    }
    int success = 0;
    for (String status : sorted(responses.keySet())) {
      if (status.matches("[0-9]+")) {
        int code = Integer.parseInt(status);
        if (code >= 200 && code < 300) {
          success = code;
          break;
        }
      }
    }

    // The path on the document's server, each {name} in it a segment of its own.
    String base = server.getRawPath() == null ? "" : server.getRawPath();
    while (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    String full = base + route;
    List<String> names = new ArrayList<>();
    StringBuilder pattern = new StringBuilder("\\A");
    int at = 0;
    Matcher match = PATH_PARAMETER.matcher(full);
    while (match.find()) {
      pattern.append(Pattern.quote(full.substring(at, match.start()))).append("([^/]+)");
      names.add(match.group(1));
      at = match.end();
    }
    pattern.append(Pattern.quote(full.substring(at))).append("\\z");

    return new Operation(
        id,
        method.toUpperCase(Locale.ROOT),
        route,
        success,
        new ArrayList<>(declared.values()),
        body,
        Pattern.compile(pattern.toString()),
        names,
        responses);
  }

  /** The schema of an operation's JSON body, or null where it takes none. */
  private Map<String, Object> bodySchema(Map<String, Object> entry) {
    if (entry == null || !entry.containsKey("requestBody")) {
      return null;
    }
    Map<String, Object> body = resolve(entry.get("requestBody"));
    Map<String, Object> media = Json.object(Json.get(Json.get(body, "content"), "application/json"));
    if (media == null) {
      throw new Unreadable("its body is not JSON");
    }
    return Json.object(Json.get(media, "schema"));
  }

  /** The object a value names, following each $ref it holds into the document. */
  private Map<String, Object> resolve(Object value) {
    Map<String, Object> current = Json.object(value);
    while (Json.get(current, "$ref") instanceof String) {
      current = pointer((String) Json.get(current, "$ref"));
    }
    return current;
  }

  /** The object a local reference, #/components/schemas/Fax, names. */
  private Map<String, Object> pointer(String reference) {
    if (!reference.startsWith("#/")) {
      throw new Unreadable(reference + " is no reference into the document");
    }
    Object value = raw;
    for (String token : reference.substring(2).split("/", -1)) {
      if (Json.object(value) == null) {
        throw new Unreadable(reference + " names nothing in the document");
      }
      value = Json.get(value, token.replace("~1", "/").replace("~0", "~"));
    }
    Map<String, Object> named = Json.object(value);
    if (named == null) {
      throw new Unreadable(reference + " names nothing in the document");
    }
    return named;
  }

  /** The operation a request is, with the values of its path's parameters; none where it is none. */
  private static final class Found {
    final Operation operation;
    final Map<String, String> values;

    Found(Operation operation, Map<String, String> values) {
      this.operation = operation;
      this.values = values;
    }
  }

  /** The operation a request is, by its method and its path on the document's server. */
  private Found find(SentRequest request) {
    Map<String, String> values = new TreeMap<>();
    URI uri = request.uri();
    if (!server.getScheme().equals(uri.getScheme())
        || !String.valueOf(server.getRawAuthority()).equals(String.valueOf(uri.getRawAuthority()))) {
      return new Found(null, values);
    }
    String path = uri.getRawPath() == null ? "" : uri.getRawPath();
    for (Operation operation : operations) {
      if (!operation.method.equals(request.method())) {
        continue;
      }
      Matcher match = operation.pattern.matcher(path);
      if (!match.matches()) {
        continue;
      }
      for (int index = 0; index < operation.names.size(); index++) {
        values.put(operation.names.get(index), unescapeSegment(match.group(index + 1)));
      }
      return new Found(operation, values);
    }
    return new Found(null, values);
  }

  /**
   * Reads a request against the document: the id of the operation it is, and each thing in it the
   * document refuses, none where it fits. version is the version its Xip-Version must name.
   */
  Reading readRequest(SentRequest request, String version) {
    Found found = find(request);
    Operation operation = found.operation;
    if (operation == null) {
      return new Reading(
          "",
          Collections.singletonList(
              request.method() + " " + request.uri() + " is no operation of the document"));
    }

    List<String> refused = new ArrayList<>();
    List<String> keys = request.values("Authorization");
    if (keys.size() != 1 || !BEARER.matcher(keys.get(0)).find()) {
      refused.add("Authorization is " + Text.quoteAll(keys) + ", not one bearer key");
    }
    List<String> versions = request.values("Xip-Version");
    if (versions.size() != 1 || !versions.get(0).equals(version)) {
      refused.add("Xip-Version is " + Text.quoteAll(versions) + ", not " + version);
    }

    Map<String, List<String>> query = queryOf(request.uri().getRawQuery());
    Set<String> takesQuery = new HashSet<>();
    for (Parameter parameter : operation.parameters) {
      List<String> given = Collections.emptyList();
      switch (parameter.in) {
        case "query":
          takesQuery.add(parameter.name);
          if (query.containsKey(parameter.name)) {
            given = query.get(parameter.name);
          }
          break;
        case "header":
          given = request.values(parameter.name);
          break;
        case "path":
          if (found.values.containsKey(parameter.name)) {
            given = Collections.singletonList(found.values.get(parameter.name));
          }
          break;
        default:
          break;
      }
      String at = parameter.in + " " + parameter.name;
      if (given.isEmpty()) {
        if (parameter.required) {
          refused.add(at + " is missing");
        }
      } else if (given.size() > 1) {
        refused.add(at + " is sent " + given.size() + " times");
      } else {
        refused.addAll(checkText(parameter.schema, given.get(0), at));
      }
    }
    for (String name : sorted(query.keySet())) {
      if (!takesQuery.contains(name)) {
        refused.add("query " + name + " is no parameter of " + operation.id);
      }
    }

    byte[] sent = request.body();
    if (operation.body != null) {
      List<String> types = request.values("Content-Type");
      String type = types.isEmpty() ? "" : types.get(0);
      if (!type.startsWith("application/json")) {
        refused.add("the body is not sent as JSON");
      }
      Object body;
      try {
        body = Json.parse(sent);
        refused.addAll(check(operation.body, body, "body"));
      } catch (IOException notJson) {
        refused.add("the body is no JSON: " + notJson.getMessage());
      }
    } else if (sent.length > 0) {
      refused.add("a body the operation takes none of");
    }
    return new Reading(operation.id, refused);
  }

  /**
   * Reads an answer to a request against the document: what in it the operation's answer for its
   * status refuses, none where it fits. An empty body is an answer that carries none.
   */
  List<String> readAnswer(SentRequest request, int status, String body) {
    Operation operation = find(request).operation;
    if (operation == null) {
      return Collections.singletonList(
          request.method() + " " + request.uri() + " is no operation of the document");
    }
    Object answer = operation.responses.get(Integer.toString(status));
    if (answer == null) {
      return Collections.singletonList(operation.id + " gives no " + status);
    }
    Map<String, Object> response;
    try {
      response = resolve(answer);
    } catch (Unreadable e) {
      return Collections.singletonList(e.getMessage());
    }
    Map<String, Object> content = Json.object(Json.get(response, "content"));
    if (content == null) {
      return body.isEmpty()
          ? Collections.emptyList()
          : Collections.singletonList(operation.id + "'s " + status + " carries no body");
    }
    Map<String, Object> schema = Json.object(Json.get(Json.get(content, "application/json"), "schema"));
    Object value;
    try {
      value = Json.parse(body.getBytes(StandardCharsets.UTF_8));
    } catch (IOException notJson) {
      return Collections.singletonList(
          operation.id + "'s " + status + " is no JSON: " + notJson.getMessage());
    }
    return check(schema, value, "answer");
  }

  /** What the operation's Xip-Version parameter refuses in version, none where it takes it. */
  List<String> versionRefused(Operation operation, String version) {
    for (Parameter parameter : operation.parameters) {
      if (parameter.in.equals("header") && parameter.name.equals("Xip-Version")) {
        return checkText(parameter.schema, version, "header Xip-Version");
      }
    }
    return Collections.singletonList(operation.id + " takes no Xip-Version");
  }

  /** A parameter's text, read as its schema's type, checked against the schema. */
  private List<String> checkText(Map<String, Object> schema, String text, String at) {
    Map<String, Object> target;
    try {
      target = resolve(schema);
    } catch (Unreadable e) {
      return Collections.singletonList(at + ": " + e.getMessage());
    }
    if ("integer".equals(Json.get(target, "type"))) {
      long number;
      try {
        number = Long.parseLong(text);
      } catch (NumberFormatException notOne) {
        return Collections.singletonList(at + " is " + Text.quote(text) + ", no integer");
      }
      return check(schema, (double) number, at);
    }
    return check(schema, text, at);
  }

  /** What the schema refuses in value, each at its place from at; none where it fits. */
  List<String> check(Map<String, Object> schema, Object value, String at) {
    List<String> refused = new ArrayList<>();
    if (schema == null) {
      return refused;
    }
    for (String keyword : sorted(schema.keySet())) {
      Object rule = schema.get(keyword);
      switch (keyword) {
        case "$ref":
          {
            Map<String, Object> target;
            try {
              target = pointer(Text.of(rule));
            } catch (Unreadable e) {
              refused.add(at + ": " + e.getMessage());
              break;
            }
            refused.addAll(check(target, value, at));
            break;
          }
        case "type":
          if (!hasType(rule, value)) {
            refused.add(at + " is " + typeOf(value) + ", not " + Text.of(rule));
          }
          break;
        case "enum":
          {
            boolean allowed = false;
            List<Object> choices = Json.array(rule);
            if (choices != null) {
              for (Object choice : choices) {
                allowed = allowed || Json.same(choice, value);
              }
            }
            if (!allowed) {
              refused.add(at + " is " + Text.of(value) + ", none of " + Text.of(rule));
            }
            break;
          }
        case "const":
          if (!Json.same(rule, value)) {
            refused.add(at + " is " + Text.of(value) + ", not " + Text.of(rule));
          }
          break;
        case "minLength":
        case "maxLength":
          if (value instanceof String && rule instanceof Double) {
            String text = (String) value;
            double length = text.codePointCount(0, text.length());
            double limit = (Double) rule;
            if ((keyword.equals("minLength") && length < limit)
                || (keyword.equals("maxLength") && length > limit)) {
              refused.add(
                  at
                      + " has "
                      + Text.of(length)
                      + " characters, past its "
                      + keyword
                      + " of "
                      + Text.of(limit));
            }
          }
          break;
        case "pattern":
          {
            if (!(value instanceof String)) {
              break;
            }
            Pattern expression;
            try {
              expression = Pattern.compile(endAnchored(Text.of(rule)));
            } catch (PatternSyntaxException e) {
              refused.add(at + ": its pattern " + Text.of(rule) + " doesn't compile: " + e.getMessage());
              break;
            }
            if (!expression.matcher((String) value).find()) {
              refused.add(
                  at + " is " + Text.quote((String) value) + ", not of the pattern " + Text.of(rule));
            }
            break;
          }
        case "minimum":
        case "maximum":
          if (value instanceof Double && rule instanceof Double) {
            double number = (Double) value;
            double limit = (Double) rule;
            if ((keyword.equals("minimum") && number < limit)
                || (keyword.equals("maximum") && number > limit)) {
              refused.add(
                  at + " is " + Text.of(number) + ", past its " + keyword + " of " + Text.of(limit));
            }
          }
          break;
        case "minItems":
        case "maxItems":
          if (value instanceof List && rule instanceof Double) {
            double count = ((List<?>) value).size();
            double limit = (Double) rule;
            if ((keyword.equals("minItems") && count < limit)
                || (keyword.equals("maxItems") && count > limit)) {
              refused.add(
                  at
                      + " has "
                      + Text.of(count)
                      + " items, past its "
                      + keyword
                      + " of "
                      + Text.of(limit));
            }
          }
          break;
        case "items":
          {
            List<Object> list = Json.array(value);
            Map<String, Object> items = Json.object(rule);
            if (list != null && items != null) {
              for (int index = 0; index < list.size(); index++) {
                refused.addAll(check(items, list.get(index), at + "[" + index + "]"));
              }
            }
            break;
          }
        case "properties":
          {
            Map<String, Object> members = Json.object(value);
            Map<String, Object> properties = Json.object(rule);
            if (members != null && properties != null) {
              for (String name : sorted(properties.keySet())) {
                Map<String, Object> property = Json.object(properties.get(name));
                if (members.containsKey(name) && property != null) {
                  refused.addAll(check(property, members.get(name), at + "." + name));
                }
              }
            }
            break;
          }
        case "required":
          {
            Map<String, Object> members = Json.object(value);
            List<Object> names = Json.array(rule);
            if (members != null && names != null) {
              for (Object name : names) {
                if (!members.containsKey(Text.of(name))) {
                  refused.add(at + "." + Text.of(name) + " is missing");
                }
              }
            }
            break;
          }
        case "additionalProperties":
          {
            Map<String, Object> members = Json.object(value);
            if (members == null) {
              break;
            }
            Map<String, Object> properties = Json.object(Json.get(schema, "properties"));
            for (String name : sorted(members.keySet())) {
              if (properties != null && properties.containsKey(name)) {
                continue;
              }
              if (rule instanceof Boolean) {
                if (!(Boolean) rule) {
                  refused.add(at + "." + name + " is no member it takes");
                }
              } else if (Json.object(rule) != null) {
                refused.addAll(check(Json.object(rule), members.get(name), at + "." + name));
              }
            }
            break;
          }
        case "propertyNames":
          {
            Map<String, Object> members = Json.object(value);
            Map<String, Object> names = Json.object(rule);
            if (members != null && names != null) {
              for (String name : sorted(members.keySet())) {
                refused.addAll(check(names, name, at + " name " + name));
              }
            }
            break;
          }
        case "anyOf":
          {
            boolean fits = false;
            List<Object> choices = Json.array(rule);
            if (choices != null) {
              for (Object choice : choices) {
                Map<String, Object> option = Json.object(choice);
                fits = fits || (option != null && check(option, value, at).isEmpty());
              }
            }
            if (!fits) {
              refused.add(at + " fits none of its anyOf");
            }
            break;
          }
        default:
          if (!ANNOTATIONS.contains(keyword)) {
            refused.add(at + ": the contract reads no " + keyword + ", which its schema gives");
          }
          break;
      }
    }
    return refused;
  }

  /**
   * A schema's pattern as Java reads it: each $ outside a set of characters as \z, since in the
   * ECMA-262 patterns a JSON schema gives, $ matches only at the end of the text, where Java's also
   * matches before a last line break.
   */
  static String endAnchored(String pattern) {
    StringBuilder translated = new StringBuilder(pattern.length() + 4);
    boolean inSet = false;
    for (int index = 0; index < pattern.length(); index++) {
      char character = pattern.charAt(index);
      if (character == '\\' && index + 1 < pattern.length()) {
        translated.append(character).append(pattern.charAt(++index));
      } else if (inSet) {
        inSet = character != ']';
        translated.append(character);
      } else if (character == '$') {
        translated.append("\\z");
      } else {
        inSet = character == '[';
        translated.append(character);
      }
    }
    return translated.toString();
  }

  /** Whether value is of the type, or one of the types, a schema's type names. */
  private static boolean hasType(Object rule, Object value) {
    if (rule instanceof String) {
      return isType((String) rule, value);
    }
    List<Object> names = Json.array(rule);
    if (names == null) {
      return false;
    }
    for (Object name : names) {
      if (isType(Text.of(name), value)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isType(String name, Object value) {
    switch (name) {
      case "null":
        return value == null;
      case "boolean":
        return value instanceof Boolean;
      case "string":
        return value instanceof String;
      case "number":
        return value instanceof Double;
      case "integer":
        return value instanceof Double && Math.rint((Double) value) == (Double) value;
      case "array":
        return value instanceof List;
      case "object":
        return value instanceof Map;
      default:
        return false;
    }
  }

  private static String typeOf(Object value) {
    for (String name :
        Arrays.asList("null", "boolean", "integer", "number", "string", "array", "object")) {
      if (isType(name, value)) {
        return name;
      }
    }
    return value.getClass().getSimpleName();
  }

  /** A query's parameters, by name, each with its values in their order. */
  private static Map<String, List<String>> queryOf(String query) {
    Map<String, List<String>> parameters = new TreeMap<>();
    if (query == null) {
      return parameters;
    }
    for (String pair : query.split("&", -1)) {
      if (pair.isEmpty()) {
        continue;
      }
      int equals = pair.indexOf('=');
      String name = unescapeForm(equals < 0 ? pair : pair.substring(0, equals));
      String value = equals < 0 ? "" : unescapeForm(pair.substring(equals + 1));
      parameters.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
    }
    return parameters;
  }

  /** A query's name or value, its plus signs spaces and each percent escape decoded. */
  private static String unescapeForm(String text) {
    try {
      return URLDecoder.decode(text, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException notEscaped) {
      return text;
    }
  }

  /** A path's segment, each percent escape decoded, a plus sign kept as one. */
  private static String unescapeSegment(String segment) {
    return unescapeForm(segment.replace("+", "%2B"));
  }

  /** Names in the order of their characters, as the document's are read. */
  static List<String> sorted(Iterable<String> names) {
    List<String> sorted = new ArrayList<>();
    for (String name : names) {
      sorted.add(name);
    }
    Collections.sort(sorted);
    return sorted;
  }
}
