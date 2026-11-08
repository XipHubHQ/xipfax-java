package com.xiphub.xipfax.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiphub.xipfax.sdk.api.DraftApi;
import com.xiphub.xipfax.sdk.api.FaxesApi;
import com.xiphub.xipfax.sdk.api.OfficesApi;
import com.xiphub.xipfax.sdk.api.PlanApi;
import com.xiphub.xipfax.sdk.api.QuoteApi;
import com.xiphub.xipfax.sdk.api.WebhookEndpointsApi;
import com.xiphub.xipfax.sdk.model.Fax;
import com.xiphub.xipfax.sdk.model.FaxEvent;
import com.xiphub.xipfax.sdk.model.NewDocument;
import com.xiphub.xipfax.sdk.model.NewWebhookEndpoint;
import com.xiphub.xipfax.sdk.model.SendDraft;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Each of the SDK's methods sends the request the API's document asks for: its method and path on
 * the document's server, the key as a bearer token, the version, and every parameter and body as
 * the document's schemas take them. The calls are typed by the SDK, so they compile only as the
 * generated code allows, and each request they send is read against the document itself, as each
 * answer the stand-in gives is. Each event the document posts is typed by the SDK as FaxEvent,
 * which the document takes as the SDK writes it. The reader is held to failing too: each thing the
 * document refuses in a request or an answer is refused, by name, so that a spec reading the SDK's
 * requests as fitting can fail.
 */
class ContractTests {
  private static final String KEY = "xipfax_key_live_example";

  /** One call of an operation, made through its API on a client. */
  @FunctionalInterface
  private interface Call {
    void on(ApiClient client) throws ApiException;
  }

  /** One call of each operation, by its operation id, with arguments the document takes. */
  private static final Map<String, Call> CALLS = calls();

  /**
   * What the stand-in answers each operation's success with, by its operation id: none for the two
   * that answer 204.
   */
  private static final Map<String, String> ANSWER_OF = answers();

  private static Map<String, Call> calls() {
    Map<String, Call> calls = new TreeMap<>();
    calls.put("getQuote", client -> new QuoteApi(client).getQuote(3));
    calls.put("getDraft", client -> new DraftApi(client).getDraft());
    calls.put(
        "addDocument",
        client ->
            new DraftApi(client)
                .addDocument(
                    "add-letter-1",
                    new NewDocument().name("letter.pdf").url("https://example.com/letter.pdf")));
    calls.put(
        "completeDocument",
        client -> new DraftApi(client).completeDocument("complete-letter-1", "doc_1"));
    calls.put("removeDocument", client -> new DraftApi(client).removeDocument("doc_1"));
    calls.put(
        "sendDraft",
        client ->
            new DraftApi(client)
                .sendDraft(
                    "send-letter-1",
                    new SendDraft().faxNumber("+1 202 555 0143").documentIds(List.of("doc_1"))));
    calls.put("listFaxes", client -> new FaxesApi(client).listFaxes(20, "fax_0", "sent"));
    calls.put("getFax", client -> new FaxesApi(client).getFax("fax_1"));
    calls.put("getConfirmation", client -> new FaxesApi(client).getConfirmation("fax_1"));
    calls.put("getPlan", client -> new PlanApi(client).getPlan());
    calls.put(
        "listOffices",
        client -> new OfficesApi(client).listOffices(5, "office_0", "Form 2848", "OH"));
    calls.put(
        "registerWebhookEndpoint",
        client ->
            new WebhookEndpointsApi(client)
                .registerWebhookEndpoint(
                    "register-endpoint-1",
                    new NewWebhookEndpoint().url("https://example.com/xipfax/events")));
    calls.put(
        "removeWebhookEndpoint",
        client -> new WebhookEndpointsApi(client).removeWebhookEndpoint("endpoint_1"));
    return calls;
  }

  private static Map<String, String> answers() {
    Map<String, String> answers = new TreeMap<>();
    answers.put("getQuote", Answers.QUOTE);
    answers.put("getDraft", "{\"id\":\"fax_1\",\"documents\":[]}");
    answers.put(
        "addDocument",
        "{\"document\":" + Answers.document("doc_1", "letter.pdf") + ",\"upload\":null}");
    answers.put("completeDocument", Answers.document("doc_1", "letter.pdf"));
    answers.put(
        "sendDraft",
        "{\"fax\":"
            + Answers.fax(
                Answers.members("status", "sending", "step", "queued", "finished_at", null))
            + ",\"paid_by\":\"plan\",\"confirm_url\":null}");
    answers.put(
        "listFaxes", "{\"data\":[" + Answers.fax() + "],\"has_more\":false,\"next_cursor\":null}");
    answers.put("getFax", Answers.fax());
    answers.put("getConfirmation", "{\"url\":\"https://example.com/confirmation.pdf\"}");
    answers.put("getPlan", Answers.NO_PLAN);
    answers.put(
        "listOffices",
        "{\"data\":[" + Answers.OFFICE + "],\"has_more\":false,\"next_cursor\":null}");
    answers.put(
        "registerWebhookEndpoint",
        "{\"id\":\"endpoint_1\",\"url\":\"https://example.com/xipfax/events\","
            + "\"created_at\":\"2026-11-07T02:00:00Z\",\"secret\":\"whsec_c3RhbmQtaW4=\"}");
    return answers;
  }

  /** The id of each operation the document names, a spec of each. */
  static Stream<String> operationIds() {
    return Contract.load().operations().stream().map(operation -> operation.id);
  }

  /**
   * The key and the version, signed as the SDK signs each request, and more headers, each a name
   * and its value.
   */
  private static Map<String, List<String>> signed(String... namesAndValues) {
    if (namesAndValues.length % 2 != 0) {
      throw new IllegalArgumentException("headers come in pairs, a name and its value");
    }
    Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    headers.put("Authorization", List.of("Bearer " + KEY));
    headers.put("Xip-Version", List.of(ApiVersion.API_VERSION));
    for (int at = 0; at < namesAndValues.length; at += 2) {
      headers.put(namesAndValues[at], List.of(namesAndValues[at + 1]));
    }
    return headers;
  }

  @Test
  void theSdkHasACallForEveryOperationTheDocumentNamesAndNoOther() {
    List<String> ids = new ArrayList<>();
    for (Contract.Operation operation : Contract.load().operations()) {
      ids.add(operation.id);
    }

    assertEquals(Contract.sorted(ids), Contract.sorted(CALLS.keySet()));
  }

  @Test
  void theSdkNamesTheVersionTheDocumentIsTheOneXipVersionTakes() {
    Contract contract = Contract.load();

    assertEquals(contract.version(), ApiVersion.API_VERSION);
    for (Contract.Operation operation : contract.operations()) {
      assertEquals(
          List.of(), contract.versionRefused(operation, ApiVersion.API_VERSION), operation.id);
    }
  }

  @ParameterizedTest
  @MethodSource("operationIds")
  void eachCallSendsItsRequestAsTheDocumentAsks(String operationId) throws ApiException {
    Contract contract = Contract.load();
    Contract.Operation operation =
        contract.operations().stream()
            .filter(each -> each.id.equals(operationId))
            .findFirst()
            .orElseThrow();
    Call call = CALLS.get(operationId);
    assertNotNull(call, "The specs make no call of " + operationId + ".");
    String answer = ANSWER_OF.getOrDefault(operationId, "");
    StandIn standIn = StandIn.same(operation.success, answer);

    call.on(new XipFax(KEY).setHttpClientBuilder(standIn.builder()));

    List<SentRequest> requests = standIn.requests();
    assertEquals(1, requests.size());
    SentRequest request = requests.get(0);
    assertEquals(List.of("Bearer " + KEY), request.values("Authorization"));
    Contract.Reading reading = contract.readRequest(request, ApiVersion.API_VERSION);
    assertEquals(operationId, reading.id);
    assertEquals(List.of(), reading.refused);
    assertEquals(List.of(), contract.readAnswer(request, operation.success, answer));
  }

  @Test
  void eachEventTheDocumentPostsIsAFaxEventItTakes() throws IOException {
    Contract contract = Contract.load();
    ObjectMapper mapper = new XipFax(KEY).getObjectMapper();
    // The fax each event tells of, by the event's type: one for each type the document posts.
    Map<String, String> faxes = new TreeMap<>();
    faxes.put(
        "fax.delivered",
        Answers.fax(
            Answers.members("price", Answers.members("amount_minor", 100, "currency", "USD"))));
    faxes.put("fax.failed", Answers.fax(Answers.members("status", "failed", "failure", "busy")));
    faxes.put(
        "fax.received",
        Answers.fax(
            Answers.members(
                "id", "fax_2",
                "direction", "received",
                "status", "received",
                "from_number", "+12025550111",
                "to_number", "+12025550100",
                "pages", 3,
                "finished_at", null,
                "time_zone", null)));
    assertEquals(Contract.sorted(contract.webhooks().keySet()), Contract.sorted(faxes.keySet()));

    for (Map.Entry<String, String> each : faxes.entrySet()) {
      String name = each.getKey();
      Fax data = mapper.readValue(each.getValue(), Fax.class);
      byte[] written =
          mapper.writeValueAsBytes(
              new FaxEvent()
                  .id("event_1")
                  .type(name)
                  .apiVersion(ApiVersion.API_VERSION)
                  .createdAt("2026-11-07T02:03:00Z")
                  .data(data));

      assertEquals(
          List.of(), contract.check(contract.webhooks().get(name), Json.parse(written), name));
      FaxEvent read = mapper.readValue(written, FaxEvent.class);
      assertEquals(data.getId(), read.getData().getId());
    }
  }

  @Test
  void theReaderTakesARequestThatFits() {
    SentRequest request =
        SentRequest.of("GET", "https://api.xipfax.com/quote?pages=3", signed(), "");

    Contract.Reading reading = Contract.load().readRequest(request, ApiVersion.API_VERSION);

    assertEquals("getQuote", reading.id);
    assertEquals(List.of(), reading.refused);
  }

  @Test
  void theReaderRefusesNoKeyTheVersionTwiceAPagePastTheMostAndAParameterItDoesntTake() {
    String version = ApiVersion.API_VERSION;
    SentRequest request =
        SentRequest.of(
            "GET",
            "https://api.xipfax.com/quote?pages=251&color=red",
            Map.of("Xip-Version", List.of(version, version)),
            "");

    Contract.Reading reading = Contract.load().readRequest(request, version);

    assertEquals("getQuote", reading.id);
    assertEquals(
        List.of(
            "Authorization is [], not one bearer key",
            "Xip-Version is [\"" + version + "\" \"" + version + "\"], not " + version,
            "header Xip-Version is sent 2 times",
            "query pages is 251, past its maximum of 250",
            "query color is no parameter of getQuote"),
        reading.refused);
  }

  @Test
  void theReaderRefusesABodyWithAMemberItDoesntTakeASizeUnderTheLeastAndNoName() {
    SentRequest request =
        SentRequest.of(
            "POST",
            "https://api.xipfax.com/draft/documents",
            signed("Content-Type", "application/json", "Idempotency-Key", "add-letter-1"),
            "{\"size_bytes\":0,\"colour\":\"red\"}");

    Contract.Reading reading = Contract.load().readRequest(request, ApiVersion.API_VERSION);

    assertEquals("addDocument", reading.id);
    assertEquals(
        List.of(
            "body.colour is no member it takes",
            "body.size_bytes is 0, past its minimum of 1",
            "body.name is missing"),
        reading.refused);
  }

  @Test
  void theReaderReadsAPatternsEndAsTheEndOfTheText() {
    // Java's $ also matches before a last line break, where the document's pattern doesn't: a name
    // that ends in one is refused.
    SentRequest request =
        SentRequest.of(
            "POST",
            "https://api.xipfax.com/draft/documents",
            signed("Content-Type", "application/json", "Idempotency-Key", "add-letter-1"),
            "{\"name\":\"letter.pdf\\n\"}");

    Contract.Reading reading = Contract.load().readRequest(request, ApiVersion.API_VERSION);

    assertEquals("addDocument", reading.id);
    assertEquals(
        List.of(
            "body.name is \"letter.pdf\\n\", not of the pattern ^[^\\p{Cc}\\p{Zl}\\p{Zp}]+$"),
        reading.refused);
  }

  @Test
  void theReaderRefusesABodyWhereTheOperationTakesNone() {
    SentRequest request =
        SentRequest.of("DELETE", "https://api.xipfax.com/draft/documents/doc_1", signed(), "{}");

    Contract.Reading reading = Contract.load().readRequest(request, ApiVersion.API_VERSION);

    assertEquals("removeDocument", reading.id);
    assertEquals(List.of("a body the operation takes none of"), reading.refused);
  }

  @Test
  void theReaderRefusesARequestToAnotherServer() {
    SentRequest request =
        SentRequest.of("GET", "https://api.example.com/quote?pages=3", signed(), "");

    Contract.Reading reading = Contract.load().readRequest(request, ApiVersion.API_VERSION);

    assertEquals("", reading.id);
    assertEquals(
        List.of("GET https://api.example.com/quote?pages=3 is no operation of the document"),
        reading.refused);
  }

  @Test
  void theReaderRefusesAFaxWhosePagesAreWordsAndWhoseStatusTheDocumentDoesntList() {
    SentRequest request = SentRequest.of("GET", "https://api.xipfax.com/faxes/fax_1", Map.of(), "");
    String answer = Answers.fax(Answers.members("pages", "two", "status", "archived"));

    assertEquals(
        List.of(
            "answer.pages is string, not [integer null]",
            "answer.status is archived, none of [draft sending delivered failed received]"),
        Contract.load().readAnswer(request, 200, answer));
  }

  @Test
  void theReaderRefusesAStatusTheOperationDoesntGive() {
    SentRequest request = SentRequest.of("GET", "https://api.xipfax.com/plan", Map.of(), "");

    assertEquals(List.of("getPlan gives no 418"), Contract.load().readAnswer(request, 418, ""));
  }

  @Test
  void theReaderRefusesABodyWhereTheAnswerCarriesNone() {
    SentRequest request =
        SentRequest.of("DELETE", "https://api.xipfax.com/draft/documents/doc_1", Map.of(), "");

    assertEquals(
        List.of("removeDocument's 204 carries no body"),
        Contract.load().readAnswer(request, 204, "{}"));
  }

  @Test
  void theReaderRefusesASchemaKeywordItDoesntRead() {
    Map<String, Object> schema = Map.of("oneOf", List.of(Map.of("type", "string")));

    assertEquals(
        List.of("value: the contract reads no oneOf, which its schema gives"),
        Contract.load().check(schema, "text", "value"));
  }
}
