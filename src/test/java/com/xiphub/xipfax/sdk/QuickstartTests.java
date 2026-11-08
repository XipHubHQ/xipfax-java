package com.xiphub.xipfax.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xiphub.xipfax.examples.Quickstart;
import com.xiphub.xipfax.sdk.model.ApiError;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The quickstart, walked against a stand-in of the API that answers as the document says: its free
 * steps first, its charged step said before it and taken only on send, and each request it sends
 * read against the document, as each answer the stand-in gives is. Its pause waits for nothing, so a
 * walk takes no time, and no walk reaches XipFax.
 */
class QuickstartTests {
  private static final String KEY = "xipfax_key_live_example";
  private static final String FAX_NUMBER = "+1 202 555 0143";
  private static final String PDF_LINK = "https://example.com/letter.pdf";

  /** The send the quickstart makes: the number it names, and the one document it added. */
  private static final Map<String, Object> ITS_SEND =
      Answers.members("fax_number", FAX_NUMBER, "document_ids", List.of("doc_1"));

  private static boolean asks(String happened) {
    return happened.startsWith("ask: ");
  }

  /**
   * The API as the quickstart finds it, its draft holding documents to begin with, and
   * addedOnXipFax too once the quickstart adds its own, as documents the client adds on xipfax.com
   * meanwhile, each by its id.
   */
  private static final class Api {
    private final Contract contract;
    private final List<String> documents;
    private final List<String> addedOnXipFax;
    private int reads;

    /** Each request, line and question of the walk, in its order. */
    final List<String> events = new ArrayList<>();

    /** Each wait between reads of the fax, in its order. */
    final List<Duration> pauses = new ArrayList<>();

    /** What the document refuses in the stand-in's answers: nothing, where it answers as it says. */
    final List<String> refused = new ArrayList<>();

    Api(Contract contract, List<String> draft, List<String> addedOnXipFax) {
      this.contract = contract;
      this.documents = new ArrayList<>(draft);
      this.addedOnXipFax = addedOnXipFax;
    }

    /** Answers a request as the API would, and reads the answer against the document. */
    StandIn.Answer answer(SentRequest request) {
      String route = request.method() + " " + request.uri().getRawPath();
      events.add(route);
      StandIn.Answer answer = route(route, request.body());
      for (String refusal : contract.readAnswer(request, answer.status, answer.body)) {
        refused.add("the answer to " + route + ": " + refusal);
      }
      return answer;
    }

    private StandIn.Answer route(String route, byte[] body) {
      switch (route) {
        case "GET /quote":
          return new StandIn.Answer(200, Answers.QUOTE);
        case "GET /offices":
          return new StandIn.Answer(
              200, "{\"data\":[" + Answers.OFFICE + "],\"has_more\":false,\"next_cursor\":null}");
        case "GET /draft":
          {
            StringJoiner listed = new StringJoiner(",");
            for (String id : documents) {
              listed.add(Answers.document(id, "letter.pdf"));
            }
            return new StandIn.Answer(200, "{\"id\":\"fax_1\",\"documents\":[" + listed + "]}");
          }
        case "POST /draft/documents":
          documents.add("doc_1");
          documents.addAll(addedOnXipFax);
          return new StandIn.Answer(
              201, "{\"document\":" + Answers.document("doc_1", "letter.pdf") + ",\"upload\":null}");
        case "DELETE /draft/documents/doc_1":
          documents.remove("doc_1");
          return new StandIn.Answer(204, "");
        case "POST /draft/send":
          // As the API does, it sends a draft only where the send names its documents, in order.
          if (!namesThem(body)) {
            return new StandIn.Answer(
                409,
                Answers.refusal(
                    "draft_changed",
                    "Your fax changed since this page showed it. Look it over, then send it again."));
          }
          return new StandIn.Answer(
              200,
              "{\"fax\":"
                  + Answers.fax(
                      Answers.members("status", "sending", "step", "queued", "finished_at", null))
                  + ",\"paid_by\":\"plan\",\"confirm_url\":null}");
        case "GET /faxes/fax_1":
          reads++;
          return reads < 2
              ? new StandIn.Answer(
                  200,
                  Answers.fax(
                      Answers.members("status", "sending", "step", "sending", "finished_at", null)))
              : new StandIn.Answer(200, Answers.fax());
        case "GET /faxes/fax_1/confirmation":
          return new StandIn.Answer(200, "{\"url\":\"https://example.com/confirmation.pdf\"}");
        default:
          return new StandIn.Answer(404, "");
      }
    }

    /** Whether a send's body names the draft's documents, in their order. */
    private boolean namesThem(byte[] body) {
      Object sent;
      try {
        sent = Json.parse(body);
      } catch (IOException notJson) {
        return false;
      }
      List<Object> ids = Json.array(Json.get(sent, "document_ids"));
      return ids != null && ids.equals(documents);
    }
  }

  /**
   * What a walk saw: each request, line and question in its order, its waits, the requests
   * themselves, and the API's refusal where one stopped it.
   */
  private static final class Walk {
    final List<String> events;
    final List<Duration> pauses;
    final List<SentRequest> requests;
    final ApiException refused;

    Walk(List<String> events, List<Duration> pauses, List<SentRequest> requests, ApiException refused) {
      this.events = events;
      this.pauses = pauses;
      this.requests = requests;
      this.refused = refused;
    }

    /** The walk's requests alone, by their routes. */
    List<String> routes() {
      List<String> routes = new ArrayList<>();
      for (String happened : events) {
        if (!happened.startsWith("say: ") && !asks(happened)) {
          routes.add(happened);
        }
      }
      return routes;
    }

    /** The body of the walk's one POST /draft/send, in plain values. */
    Object sent() throws IOException {
      List<SentRequest> sends = new ArrayList<>();
      for (SentRequest request : requests) {
        if (request.method().equals("POST") && "/draft/send".equals(request.uri().getRawPath())) {
          sends.add(request);
        }
      }
      assertEquals(1, sends.size(), "the walk's sends of its draft");
      return Json.parse(sends.get(0).body());
    }
  }

  /**
   * Walks the quickstart against the API, its draft holding draft and gaining addedOnXipFax, typed
   * being what you type when it asks, and reads each request it sent, and each answer it was given,
   * against the document.
   */
  private static Walk walkOf(
      List<String> draft, List<String> addedOnXipFax, String typed, String to, String pdfUrl)
      throws InterruptedException {
    Contract contract = Contract.load();
    Api api = new Api(contract, draft, addedOnXipFax);
    StandIn standIn = new StandIn(api::answer);
    ApiClient client = new XipFax(KEY).setHttpClientBuilder(standIn.builder());
    Quickstart.Terminal terminal =
        new Quickstart.Terminal() {
          @Override
          public void say(String line) {
            api.events.add("say: " + line);
          }

          @Override
          public String ask(String question) {
            api.events.add("ask: " + question);
            return typed;
          }

          @Override
          public void pause(Duration wait) {
            api.pauses.add(wait);
          }
        };

    ApiException refused = null;
    try {
      Quickstart.run(client, to, pdfUrl, terminal);
    } catch (ApiException refusal) {
      refused = refusal;
    }

    assertTrue(api.refused.isEmpty(), () -> Text.quoteAll(api.refused));
    for (SentRequest request : standIn.requests()) {
      Contract.Reading reading = contract.readRequest(request, contract.version());
      assertTrue(
          reading.refused.isEmpty(),
          () -> request.method() + " " + request.uri() + ": " + Text.quoteAll(reading.refused));
    }
    return new Walk(api.events, api.pauses, standIn.requests(), refused);
  }

  @Test
  void theQuickstartTakesTheFreeStepsAloneWhereItHasNoNumberOrPdf() throws Exception {
    Walk walk = walkOf(List.of(), List.of(), "send", null, null);

    assertNull(walk.refused);
    assertEquals(List.of("GET /quote", "GET /offices"), walk.routes());
    assertFalse(walk.events.stream().anyMatch(QuickstartTests::asks), () -> Text.quoteAll(walk.events));
  }

  @Test
  void theQuickstartSaysTheFaxIsRealAndChargedSendsItOnlyOnceSendIsTypedAndFollowsIt()
      throws Exception {
    Walk walk = walkOf(List.of(), List.of(), "send", FAX_NUMBER, PDF_LINK);

    assertNull(walk.refused);
    assertEquals(
        List.of(
            "GET /quote",
            "GET /offices",
            "GET /draft",
            "POST /draft/documents",
            "POST /draft/send",
            "GET /faxes/fax_1",
            "GET /faxes/fax_1",
            "GET /faxes/fax_1/confirmation"),
        walk.routes());
    int added = walk.events.indexOf("POST /draft/documents");
    int warned = walk.events.indexOf("say: " + Quickstart.REAL_AND_CHARGED);
    int asked =
        IntStream.range(0, walk.events.size())
            .filter(index -> asks(walk.events.get(index)))
            .findFirst()
            .orElse(-1);
    int sent = walk.events.indexOf("POST /draft/send");
    assertTrue(
        added >= 0 && warned > added && asked > warned && sent > asked,
        () ->
            "it added at "
                + added
                + ", warned at "
                + warned
                + ", asked at "
                + asked
                + " and sent at "
                + sent
                + ": "
                + Text.quoteAll(walk.events));
    assertTrue(
        walk.events.contains("say: Added letter.pdf, 2 pages."), () -> Text.quoteAll(walk.events));

    Object send = walk.sent();
    assertTrue(Json.same(ITS_SEND, send), () -> "the send was " + Text.of(send));
    List<String> keys = new ArrayList<>();
    for (SentRequest request : walk.requests) {
      if (request.method().equals("POST")) {
        List<String> values = request.values("Idempotency-Key");
        assertEquals(1, values.size(), () -> request.uri() + "'s Idempotency-Key");
        keys.add(values.get(0));
      }
    }
    assertEquals(keys.size(), new HashSet<>(keys).size(), () -> Text.quoteAll(keys));
    assertEquals(List.of(Quickstart.READ_EVERY, Quickstart.READ_EVERY), walk.pauses);
  }

  @Test
  void theQuickstartSendsNothingOnAnyOtherAnswerAndTakesItsDocumentBackOutOfTheDraft()
      throws Exception {
    Walk walk = walkOf(List.of(), List.of(), "no", FAX_NUMBER, PDF_LINK);

    assertNull(walk.refused);
    assertEquals(
        List.of(
            "GET /quote",
            "GET /offices",
            "GET /draft",
            "POST /draft/documents",
            "DELETE /draft/documents/doc_1"),
        walk.routes());
  }

  @Test
  void theQuickstartNamesOnlyTheDocumentItAddedSoADraftThatGainedAnotherOnXipFaxIsRefused()
      throws Exception {
    Walk walk = walkOf(List.of(), List.of("doc_2"), "send", FAX_NUMBER, PDF_LINK);

    ApiException refused = walk.refused;
    assertNotNull(refused);
    assertEquals(409, refused.getCode());
    ApiError error =
        new XipFax(KEY).getObjectMapper().readValue(refused.getResponseBody(), ApiError.class);
    assertEquals("draft_changed", error.getError().getCode());
    assertEquals(
        List.of(
            "GET /quote", "GET /offices", "GET /draft", "POST /draft/documents", "POST /draft/send"),
        walk.routes());
    Object send = walk.sent();
    assertTrue(Json.same(ITS_SEND, send), () -> "the send was " + Text.of(send));
  }

  @Test
  void theQuickstartAddsNothingToADraftThatAlreadyHoldsDocuments() throws Exception {
    Walk walk = walkOf(List.of("doc_0"), List.of(), "send", FAX_NUMBER, PDF_LINK);

    assertNull(walk.refused);
    assertEquals(List.of("GET /quote", "GET /offices", "GET /draft"), walk.routes());
  }

  /** Links, each with the name the quickstart gives its PDF. */
  static Stream<Arguments> links() {
    return Stream.of(
        Arguments.of("https://example.com/letter.pdf", "letter.pdf"),
        Arguments.of("https://example.com/forms/Form%202848.pdf", "Form 2848.pdf"),
        Arguments.of("https://example.com/forms/", "forms"),
        Arguments.of("https://example.com", "document.pdf"),
        Arguments.of("https://example.com/%FF.pdf", "%FF.pdf"),
        Arguments.of("https://example.com/" + "é".repeat(300), "é".repeat(255)));
  }

  @ParameterizedTest
  @MethodSource("links")
  void theDocumentIsNamedAsItsLinkEnds(String link, String name) {
    assertEquals(name, Quickstart.nameOf(link));
  }
}
