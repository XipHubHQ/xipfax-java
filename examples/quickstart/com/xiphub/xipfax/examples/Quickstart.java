package com.xiphub.xipfax.examples;

import com.xiphub.xipfax.sdk.ApiClient;
import com.xiphub.xipfax.sdk.ApiException;
import com.xiphub.xipfax.sdk.api.DraftApi;
import com.xiphub.xipfax.sdk.api.FaxesApi;
import com.xiphub.xipfax.sdk.api.OfficesApi;
import com.xiphub.xipfax.sdk.api.QuoteApi;
import com.xiphub.xipfax.sdk.model.Draft;
import com.xiphub.xipfax.sdk.model.DraftDocument;
import com.xiphub.xipfax.sdk.model.Fax;
import com.xiphub.xipfax.sdk.model.NewDocument;
import com.xiphub.xipfax.sdk.model.Office;
import com.xiphub.xipfax.sdk.model.OfficePage;
import com.xiphub.xipfax.sdk.model.Quote;
import com.xiphub.xipfax.sdk.model.SendDraft;
import com.xiphub.xipfax.sdk.model.Sent;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * XipFax's quickstart through the SDK, the steps of
 * https://developer.xipfax.com/docs/guides/quickstart/ in their order. The free steps come first: a
 * quote, and the offices that take a form. Then, only where you name a fax number of yours and a
 * PDF, a real fax, charged, which it says before it sends, and sends only once you type send. Main
 * runs it in your terminal.
 */
public final class Quickstart {
  /** How long the quickstart waits between reads of the fax. */
  public static final Duration READ_EVERY = Duration.ofSeconds(10);

  /** How many reads of the fax it makes, at most. */
  public static final int READS = 30;

  /** What the step that sends says first, as the docs' quickstart says it. */
  public static final String REAL_AND_CHARGED =
      "This step sends a real fax, and it’s charged. It goes to the number you name, so name one of"
          + " your own. Its pages count on your plan, or where you have no plan its price is held on"
          + " the card you saved, and taken once it’s delivered. A fax that fails costs nothing.";

  /** How the quickstart talks to you. */
  public interface Terminal {
    /**
     * Tells you a line.
     *
     * @param line the line
     */
    void say(String line);

    /**
     * Asks you a question.
     *
     * @param question the question
     * @return what you typed, or null where nothing was
     */
    String ask(String question);

    /**
     * Waits between reads of the fax.
     *
     * @param wait how long
     * @throws InterruptedException if the wait is stopped
     */
    void pause(Duration wait) throws InterruptedException;
  }

  /** Never made: the quickstart is its steps alone. */
  private Quickstart() {}

  /**
   * Takes the quickstart's steps, each request sent by the client. The API's refusal stops it,
   * thrown as the SDK's ApiException, whose body is the API's error, which Main prints.
   *
   * @param client the client, made from your API key
   * @param to a fax number of yours, where the fax goes; without it, only the free steps run
   * @param pdfUrl a public https link to a PDF, the fax's pages; without it, only the free steps run
   * @param terminal how the quickstart talks to you
   * @throws ApiException if the API refuses a step, or can't be reached
   * @throws InterruptedException if a wait between reads of the fax is stopped
   */
  public static void run(ApiClient client, String to, String pdfUrl, Terminal terminal)
      throws ApiException, InterruptedException {
    QuoteApi quotes = new QuoteApi(client);
    OfficesApi offices = new OfficesApi(client);
    DraftApi draft = new DraftApi(client);
    FaxesApi faxes = new FaxesApi(client);

    // 1. What a fax costs. This costs nothing, and it shows your key works.
    Quote quote = quotes.getQuote(1);
    terminal.say(
        "A one-page fax costs " + quote.getPriceShown() + " without a plan: " + quote.getTerms());

    // 2. Where a form goes: the offices that take Form 2848 from Ohio, and their numbers. Also free.
    OfficePage found = offices.listOffices(3, null, "Form 2848", "OH");
    for (Office office : found.getData()) {
      terminal.say(office.getName() + " takes Form 2848 at " + office.getFaxNumber() + ".");
    }

    if (isEmpty(to) || isEmpty(pdfUrl)) {
      return;
    }

    // 3. Your draft, the fax you prepare. This sends only its own document, so it starts empty.
    Draft before = draft.getDraft();
    if (!before.getDocuments().isEmpty()) {
      terminal.say(
          "Your draft already holds documents. Send or remove them on xipfax.com first, so that"
              + " this sends only its own.");
      return;
    }

    // 4. Add the PDF by its link. Each POST names a key of its own, so a retry can't add it twice.
    NewDocument document = new NewDocument().name(nameOf(pdfUrl)).url(pdfUrl);
    DraftDocument added = draft.addDocument(newKey(), document).getDocument();
    terminal.say("Added " + added.getName() + pagesOf(added) + ".");

    // 5. Send it, a real fax, charged: said first, and sent only once you type send.
    terminal.say(REAL_AND_CHARGED);
    String answer = terminal.ask("Type send to fax it to " + to + ", or anything else to stop: ");
    if (answer == null || !answer.strip().equalsIgnoreCase("send")) {
      draft.removeDocument(added.getId());
      terminal.say("Nothing was sent, and the document is out of your draft again.");
      return;
    }
    // The send names the documents to send: the one this added, alone. A draft whose documents
    // aren't these is refused, so that nothing you didn't name is sent.
    List<String> documentIds = new ArrayList<>();
    documentIds.add(added.getId());
    Sent sent = draft.sendDraft(newKey(), new SendDraft().faxNumber(to).documentIds(documentIds));
    if (!isEmpty(sent.getConfirmUrl())) {
      terminal.say(
          "Nothing was sent or held: the fax waits for you on xipfax.com, " + sent.getConfirmUrl());
      return;
    }
    terminal.say("Sent, paid by " + sent.getPaidBy() + ": fax " + sent.getFax().getId() + ".");

    // 6. Follow the fax until it's delivered or fails, then read its confirmation.
    Fax fax = sent.getFax();
    String shown = "";
    for (int read = 0; read < READS && !isFinished(fax); read++) {
      terminal.pause(READ_EVERY);
      fax = faxes.getFax(fax.getId());
      String stands =
          isEmpty(fax.getStep()) ? fax.getStatus() : fax.getStatus() + ": " + fax.getStep();
      if (!stands.equals(shown)) {
        terminal.say(stands);
      }
      shown = stands;
    }
    if ("delivered".equals(fax.getStatus())) {
      String confirmation = faxes.getConfirmation(fax.getId()).getUrl();
      terminal.say("Its confirmation, readable for a few minutes: " + confirmation);
    } else if ("failed".equals(fax.getStatus())) {
      terminal.say("It failed, " + fax.getFailure() + ", so it costs nothing.");
    } else {
      terminal.say(
          "It's still "
              + fax.getStatus()
              + ". Read it later: new FaxesApi(client).getFax(\""
              + fax.getId()
              + "\").");
    }
  }

  /**
   * A key of a request's own for its Idempotency-Key, a random UUID.
   *
   * @return the key
   */
  public static String newKey() {
    return UUID.randomUUID().toString();
  }

  /**
   * The PDF's name, as its link ends: letter.pdf. Each percent-encoded character of it is decoded,
   * and a name that isn't percent-encoded text is kept as it is. It keeps 255 characters at most, as
   * the API takes a name.
   *
   * @param link the PDF's link
   * @return its name
   */
  public static String nameOf(String link) {
    String path;
    try {
      path = new URI(link).getRawPath();
    } catch (URISyntaxException notALink) {
      // A link that isn't one ends in no name.
      path = null;
    }
    String name = "document.pdf";
    if (path != null) {
      for (String segment : path.split("/")) {
        if (!segment.isEmpty()) {
          name = segment;
        }
      }
      String text = unescaped(name);
      if (text != null) {
        name = text;
      }
    }
    if (name.codePointCount(0, name.length()) > 255) {
      name = name.substring(0, name.offsetByCodePoints(0, 255));
    }
    return name;
  }

  /**
   * A segment of a path, each percent escape decoded.
   *
   * @param segment the segment, as its link writes it
   * @return its text, or null where it isn't percent-encoded UTF-8
   */
  private static String unescaped(String segment) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    int at = 0;
    while (at < segment.length()) {
      char character = segment.charAt(at);
      if (character == '%') {
        if (at + 2 >= segment.length()) {
          return null;
        }
        int high = Character.digit(segment.charAt(at + 1), 16);
        int low = Character.digit(segment.charAt(at + 2), 16);
        if (high < 0 || low < 0) {
          return null;
        }
        bytes.write(high << 4 | low);
        at += 3;
      } else {
        int point = segment.codePointAt(at);
        byte[] encoded = new String(Character.toChars(point)).getBytes(StandardCharsets.UTF_8);
        bytes.write(encoded, 0, encoded.length);
        at += Character.charCount(point);
      }
    }
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes.toByteArray()))
          .toString();
    } catch (CharacterCodingException notText) {
      return null;
    }
  }

  /**
   * A document's pages, once XipFax has counted them.
   *
   * @param document the document
   * @return ", 2 pages", or nothing where they aren't counted yet
   */
  public static String pagesOf(DraftDocument document) {
    Integer count = document.getPageCount();
    if (count == null) {
      return "";
    }
    return count == 1 ? ", 1 page" : ", " + count + " pages";
  }

  /**
   * Whether a fax is done with: delivered, or failed.
   *
   * @param fax the fax
   * @return whether it is
   */
  private static boolean isFinished(Fax fax) {
    return "delivered".equals(fax.getStatus()) || "failed".equals(fax.getStatus());
  }

  /**
   * Whether a setting is missing: unset, or set to nothing.
   *
   * @param text the setting
   * @return whether it is
   */
  static boolean isEmpty(String text) {
    return text == null || text.isEmpty();
  }
}
