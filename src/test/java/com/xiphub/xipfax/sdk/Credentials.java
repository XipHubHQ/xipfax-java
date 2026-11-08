package com.xiphub.xipfax.sdk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What no spec's environment may hold: a variable shaped like a credential, or named for XipFax. A
 * Java process can't take a variable out of its own environment, so scripts/test.sh runs the specs
 * in one holding PATH, HOME and JAVA_HOME alone: the day a real key sits in a developer's shell, a
 * spec that could reach the API still has nothing to reach it with, and CredentialsTests say so.
 */
final class Credentials {
  /** A variable that is a secret in itself, in any case. */
  private static final Pattern SECRET =
      Pattern.compile(
          "TOKEN|SECRET|PASSWORD|CREDENTIAL|AUTH|APIKEY|API_KEY|_KEY\\z|_KEYS\\z|COOKIE|PRIVATE",
          Pattern.CASE_INSENSITIVE);

  /** The variables XipFax's own examples read, such as XIPFAX_API_KEY. */
  private static final Pattern XIPFAX_VARIABLE =
      Pattern.compile("\\AXIPFAX_", Pattern.CASE_INSENSITIVE);

  private Credentials() {}

  /** Whether a spec's process has no business holding the variable. */
  static boolean shaped(String name) {
    return SECRET.matcher(name).find() || XIPFAX_VARIABLE.matcher(name).find();
  }

  /** The name of each such variable in the process's environment, sorted. */
  static List<String> left() {
    return left(System.getenv());
  }

  /** The name of each such variable in the environment, sorted. */
  static List<String> left(Map<String, String> environment) {
    List<String> left = new ArrayList<>();
    for (String name : environment.keySet()) {
      if (shaped(name)) {
        left.add(name);
      }
    }
    Collections.sort(left);
    return left;
  }
}
