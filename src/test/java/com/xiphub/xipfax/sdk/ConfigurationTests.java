package com.xiphub.xipfax.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xiphub.xipfax.sdk.api.FaxesApi;
import com.xiphub.xipfax.sdk.api.PlanApi;
import com.xiphub.xipfax.sdk.model.ApiError;
import com.xiphub.xipfax.sdk.model.Fax;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What XipFax, the SDK's hand-written part, makes each request carry and where it sends it, and
 * what the SDK makes of a refusal and of an answer it didn't expect.
 */
class ConfigurationTests {
  /** The one request GET /plan sends from the client, to a stand-in of its own. */
  private static SentRequest planRequestOf(XipFax client) throws ApiException {
    StandIn standIn = StandIn.same(200, Answers.NO_PLAN);
    client.setHttpClientBuilder(standIn.builder());
    new PlanApi(client).getPlan();
    List<SentRequest> requests = standIn.requests();
    assertEquals(1, requests.size());
    return requests.get(0);
  }

  @Test
  void itRefusesToStartWithoutAKey() {
    for (String apiKey : Arrays.asList(null, "", "   ")) {
      assertThrows(IllegalArgumentException.class, () -> new XipFax(apiKey));
    }
  }

  @Test
  void itRefusesAnAddressThatIsntHttpOrHttps() {
    for (String baseUrl : Arrays.asList("", "api.example.com", "ftp://api.example.com")) {
      assertThrows(IllegalArgumentException.class, () -> new XipFax("key-a", baseUrl));
    }
  }

  @Test
  void itSendsToTheApisOwnAddressUnlessGivenAnother() throws ApiException {
    SentRequest own = planRequestOf(new XipFax("key-a"));
    assertEquals("https://api.xipfax.com/plan", own.uri().toString());

    SentRequest other = planRequestOf(new XipFax("key-a", "https://api.example.com/"));
    assertEquals("https://api.example.com/plan", other.uri().toString());
  }

  @Test
  void eachClientKeepsItsKeyToItself() throws ApiException {
    XipFax first = new XipFax("key-a");
    XipFax second = new XipFax("key-b");

    SentRequest a = planRequestOf(first);
    SentRequest b = planRequestOf(second);

    assertEquals(List.of("Bearer key-a"), a.values("Authorization"));
    assertEquals(List.of("Bearer key-b"), b.values("Authorization"));
  }

  @Test
  void eachRequestNamesTheVersionTheSdkIsGeneratedFrom() throws ApiException {
    SentRequest request = planRequestOf(new XipFax("key-a"));

    assertEquals(ApiVersion.API_VERSION, XipFax.API_VERSION);
    assertEquals(List.of(ApiVersion.API_VERSION), request.values("Xip-Version"));
  }

  @Test
  void aStepSetOnTheClientRunsAndTheRequestStillNamesTheKeyAndTheVersion() throws ApiException {
    XipFax client = new XipFax("key-a");
    client.setRequestInterceptor(
        request ->
            request
                .setHeader("Authorization", "Bearer key-b")
                .setHeader("Xip-Version", "2020-01-01")
                .setHeader("X-Trace", "trace-1"));

    SentRequest stepped = planRequestOf(client);

    assertEquals(List.of("trace-1"), stepped.values("X-Trace"));
    assertEquals(List.of("Bearer key-a"), stepped.values("Authorization"));
    assertEquals(List.of(ApiVersion.API_VERSION), stepped.values("Xip-Version"));

    client.setRequestInterceptor(null);
    SentRequest plain = planRequestOf(client);

    assertEquals(List.of(), plain.values("X-Trace"));
    assertEquals(List.of("Bearer key-a"), plain.values("Authorization"));
    assertEquals(List.of(ApiVersion.API_VERSION), plain.values("Xip-Version"));
  }

  @Test
  void aRefusalIsTheApisOwnError() throws IOException {
    String refusal = Answers.refusal("authentication_failed", "That key isn’t one of XipFax’s.");
    XipFax client = new XipFax("key-a");
    client.setHttpClientBuilder(StandIn.same(401, refusal).builder());
    PlanApi plan = new PlanApi(client);

    ApiException refused = assertThrows(ApiException.class, () -> plan.getPlan());

    assertEquals(401, refused.getCode());
    ApiError error = client.getObjectMapper().readValue(refused.getResponseBody(), ApiError.class);
    assertEquals("authentication_failed", error.getError().getCode());
    assertEquals("req_1", error.getError().getRequestId());
  }

  @Test
  void anAnswerThatGainsAMemberIsReadAndCarriesItThrough() throws ApiException, IOException {
    // The document's Additions rule: within a version, an answer may gain a member, and an open set
    // a new value. Here the fax gains tracking, and its status is one the SDK has never seen.
    String gained =
        Answers.fax(
            Answers.members(
                "status", "archived", "tracking", Answers.members("carrier", "stand-in")));
    XipFax client = new XipFax("key-a");
    client.setHttpClientBuilder(StandIn.same(200, gained).builder());

    Fax fax = new FaxesApi(client).getFax("fax_1");

    assertEquals("archived", fax.getStatus());
    Object carried = Json.parse(client.getObjectMapper().writeValueAsBytes(fax));
    assertEquals("stand-in", Json.get(Json.get(carried, "tracking"), "carrier"));
  }
}
