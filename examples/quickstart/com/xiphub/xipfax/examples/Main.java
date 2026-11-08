package com.xiphub.xipfax.examples;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiphub.xipfax.sdk.ApiException;
import com.xiphub.xipfax.sdk.XipFax;
import com.xiphub.xipfax.sdk.model.ApiError;
import com.xiphub.xipfax.sdk.model.ApiErrorObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.time.Duration;

/**
 * Runs XipFax's quickstart in your terminal, from a clone of this repository: {@code
 * XIPFAX_API_KEY=xipfax_key_live_… mvn --quiet test-compile exec:java}. That takes the free steps
 * alone. To send a fax too, real and charged, name a fax number of yours and a public link to a PDF:
 * {@code XIPFAX_TO='+1 202 555 0143' XIPFAX_PDF_URL=https://example.com/letter.pdf
 * XIPFAX_API_KEY=… mvn --quiet test-compile exec:java}.
 */
public final class Main {
  /** Never made: the program is its main alone. */
  private Main() {}

  /**
   * Runs the quickstart, ending with exit status 1 where it stops on a refusal.
   *
   * @param arguments none: the quickstart reads XIPFAX_API_KEY, XIPFAX_TO and XIPFAX_PDF_URL
   */
  public static void main(String[] arguments) {
    XipFax client;
    try {
      client = new XipFax(System.getenv("XIPFAX_API_KEY"));
    } catch (IllegalArgumentException noKey) {
      System.err.println(
          "Set XIPFAX_API_KEY to a key you made in the developer console:"
              + " https://developer.xipfax.com/console/");
      System.exit(1);
      return;
    }
    String to = System.getenv("XIPFAX_TO");
    String pdfUrl = System.getenv("XIPFAX_PDF_URL");

    BufferedReader typed =
        new BufferedReader(new InputStreamReader(System.in, Charset.defaultCharset()));
    Quickstart.Terminal terminal =
        new Quickstart.Terminal() {
          @Override
          public void say(String line) {
            System.out.println(line);
          }

          @Override
          public String ask(String question) {
            System.out.print(question);
            System.out.flush();
            // A terminal that closes answers what was typed before it did, which sends only if
            // that was send.
            try {
              return typed.readLine();
            } catch (IOException unread) {
              return null;
            }
          }

          @Override
          public void pause(Duration wait) throws InterruptedException {
            Thread.sleep(wait.toMillis());
          }
        };
    try {
      Quickstart.run(client, to, pdfUrl, terminal);
    } catch (ApiException refused) {
      System.err.println(refusalOf(refused, client.getObjectMapper()));
      System.exit(1);
      return;
    } catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
      System.err.println("Stopped while it waited to read the fax again.");
      System.exit(1);
      return;
    }
    if (Quickstart.isEmpty(to) || Quickstart.isEmpty(pdfUrl)) {
      System.out.println(
          "Those steps cost nothing. To send a fax too, run it again with XIPFAX_TO, a fax number of"
              + " yours, and XIPFAX_PDF_URL, a public link to a PDF.");
    }
  }

  /**
   * The API's refusal as a line: its code and its sentence, where its body holds the API's error, or
   * the exception's message, as for an API that wasn't reached.
   *
   * @param refused the refusal
   * @param mapper the client's reader of JSON
   * @return the line
   */
  private static String refusalOf(ApiException refused, ObjectMapper mapper) {
    String body = refused.getResponseBody();
    ApiErrorObject error = null;
    if (body != null && !body.isEmpty()) {
      try {
        ApiError answer = mapper.readValue(body, ApiError.class);
        error = answer == null ? null : answer.getError();
      } catch (IOException noErrorOfTheApis) {
        error = null;
      }
    }
    if (error == null) {
      return refused.getMessage();
    }
    return "XipFax refused it, " + error.getCode() + ": " + error.getMessage();
  }
}
