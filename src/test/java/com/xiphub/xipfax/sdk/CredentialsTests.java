package com.xiphub.xipfax.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The specs run with no credential in their environment, as scripts/test.sh runs them. */
class CredentialsTests {
  @Test
  void theEnvironmentHoldsNoCredential() {
    assertEquals(
        List.of(),
        Credentials.left(),
        "Run the specs by scripts/test.sh, which runs them with no credential in their"
            + " environment.");
  }

  @Test
  void aKeyATokenAndTheXipFaxVariablesAreCredentialsAndAPlainSettingIsNot() {
    Map<String, String> environment = new HashMap<>();
    environment.put("XIPFAX_API_KEY", "stand-in");
    environment.put("XIPFAX_TO", "+1 202 555 0143");
    environment.put("GITHUB_TOKEN", "stand-in");
    environment.put("PLAIN_SETTING", "kept");

    assertEquals(
        List.of("GITHUB_TOKEN", "XIPFAX_API_KEY", "XIPFAX_TO"), Credentials.left(environment));
  }
}
