# XipFax for Java

Send faxes from your code with [XipFax](https://xipfax.com): quote a fax, add your PDFs, send them
to a number or a government office, and follow each fax until it's delivered. The SDK is generated
from the API's own document, so its methods and types are the API's. It runs on Java 11 or later,
sends with Java's own HTTP client, `java.net.http`, and takes Jackson.

## Install

With Maven:

```xml
<dependency>
  <groupId>com.xiphub</groupId>
  <artifactId>xipfax-sdk</artifactId>
  <version>0.1.0</version>
</dependency>
```

With Gradle:

```kotlin
implementation("com.xiphub:xipfax-sdk:0.1.0")
```

Its classes are in `com.xiphub.xipfax.sdk`, the APIs in `com.xiphub.xipfax.sdk.api` and the models
in `com.xiphub.xipfax.sdk.model`, and the jar names its module `com.xiphub.xipfax.sdk`.

## Make an API key

Sign in to the [developer console](https://developer.xipfax.com/console/), open API keys and make a
key. You see it whole just once, so copy it then. Every key is live: what it sends is real.

Keep the key on your server. A key in a web page or an app is anyone's.

## Use it

```java
import com.xiphub.xipfax.sdk.ApiException;
import com.xiphub.xipfax.sdk.XipFax;
import com.xiphub.xipfax.sdk.api.QuoteApi;
import com.xiphub.xipfax.sdk.model.ApiError;
import com.xiphub.xipfax.sdk.model.ApiErrorObject;
import com.xiphub.xipfax.sdk.model.Quote;

public class Example {
  public static void main(String[] args) throws Exception {
    XipFax client = new XipFax(System.getenv("XIPFAX_API_KEY"));
    QuoteApi quotes = new QuoteApi(client);

    try {
      Quote quote = quotes.getQuote(3);
      System.out.println(quote.getPriceShown() + ": " + quote.getTerms());
    } catch (ApiException refused) {
      String body = refused.getResponseBody();
      ApiError answer =
          body == null ? null : client.getObjectMapper().readValue(body, ApiError.class);
      ApiErrorObject error = answer == null ? null : answer.getError();
      if (error == null) {
        throw refused;
      }
      System.err.println(refused.getCode() + " " + error.getCode() + ": " + error.getMessage());
    }
  }
}
```

`XipFax` is the client, made from your key, and each request it sends carries it. Make each API from
it: the SDK's APIs are the [API reference](https://developer.xipfax.com/docs/api/)'s sections,
`QuoteApi`, `DraftApi`, `FaxesApi`, `PlanApi`, `OfficesApi` and `WebhookEndpointsApi`. Each method
takes what its request carries as its arguments, `getQuote(3)` or
`addDocument(idempotencyKey, document)`, and has a form that takes more headers last, as a `Map`,
and a form that answers the status and the headers too, `getQuoteWithHttpInfo(3)`.

Where the API refuses, the method throws an `ApiException`: its `getCode()` is the status, and its
`getResponseBody()` the answer's body, which the client's `getObjectMapper()` reads as the API's
error object, `ApiError`, whose `getError().getCode()` is the one to branch on. A request that never
reached XipFax throws an `ApiException` too, with no body, its cause what the HTTP client threw, and
the example throws that on.

An API takes the client's settings when it's made, so set any first: `setHttpClientBuilder`, for a
proxy or an executor of your own, before `setConnectTimeout` and `setReadTimeout`; and
`setRequestInterceptor`, for a step each request goes through before it's sent, after which the
client still names your key and the version. `new XipFax(apiKey, baseUrl)` sends to another address
than `https://api.xipfax.com`.

## The quickstart

[`examples/quickstart`](https://github.com/XipHubHQ/xipfax-java/tree/main/examples/quickstart) takes
the steps of [XipFax's quickstart](https://developer.xipfax.com/docs/guides/quickstart/) through the
SDK. Run it from a clone of this repository, with Java 11 or later and Maven:

```sh
XIPFAX_API_KEY=xipfax_key_live_… mvn --quiet test-compile exec:java
```

That takes the free steps alone: what a fax costs, and the offices that take Form 2848 from Ohio,
with their fax numbers.

To send a fax too, name a fax number of yours and a public https link to a PDF:

```sh
XIPFAX_TO='+1 202 555 0143' XIPFAX_PDF_URL=https://example.com/letter.pdf \
  XIPFAX_API_KEY=xipfax_key_live_… mvn --quiet test-compile exec:java
```

**That step sends a real fax, and it's charged.** Its pages count on your plan, or where you have no
plan its price is held on the card you saved, and taken once it's delivered. A fax that fails costs
nothing. The quickstart says so before it sends, and sends only once you type `send`. Then it
follows the fax until it's delivered, and gives the link to its confirmation.

## Methods

| Method                                        | Request                                        | What it does                                                                         |
| --------------------------------------------- | ---------------------------------------------- | ------------------------------------------------------------------------------------ |
| `QuoteApi.getQuote`                           | `GET /quote`                                   | The price of a fax of so many pages, sent without a plan.                            |
| `DraftApi.getDraft`                           | `GET /draft`                                   | Your draft, the fax you prepare, its documents in the order it sends them.           |
| `DraftApi.addDocument`                        | `POST /draft/documents`                        | Adds a PDF to the draft, by a link XipFax fetches or by an upload.                   |
| `DraftApi.completeDocument`                   | `POST /draft/documents/{document_id}/complete` | Reads an uploaded PDF back, its pages counted.                                       |
| `DraftApi.removeDocument`                     | `DELETE /draft/documents/{document_id}`        | Takes a document out of the draft.                                                   |
| `DraftApi.sendDraft`                          | `POST /draft/send`                             | Sends the draft to a number or an office.                                            |
| `FaxesApi.listFaxes`                          | `GET /faxes`                                   | The faxes you sent and received, newest first, a page at a time.                     |
| `FaxesApi.getFax`                             | `GET /faxes/{fax_id}`                          | A fax, where it stands now.                                                          |
| `FaxesApi.getConfirmation`                    | `GET /faxes/{fax_id}/confirmation`             | A delivered fax's confirmation, by a link that reads it for a few minutes.           |
| `PlanApi.getPlan`                             | `GET /plan`                                    | Your plan, or none.                                                                  |
| `OfficesApi.listOffices`                      | `GET /offices`                                 | The government offices XipFax gives the fax numbers of.                              |
| `WebhookEndpointsApi.registerWebhookEndpoint` | `POST /webhook-endpoints`                      | Registers an endpoint of your server for every event, its signing secret shown once. |
| `WebhookEndpointsApi.removeWebhookEndpoint`   | `DELETE /webhook-endpoints/{endpoint_id}`      | Takes an endpoint away: no event goes to it from then on.                            |

### Upload a PDF from your disk

Give its size, send its bytes to the link the answer gives, then complete it:

```java
byte[] pdf = Files.readAllBytes(Path.of("letter.pdf"));
DraftApi drafts = new DraftApi(client);
DocumentAdded added =
    drafts.addDocument(addKey, new NewDocument().name("letter.pdf").sizeBytes(pdf.length));
DocumentAddedUpload upload = added.getUpload();
if (upload == null) {
  throw new IllegalStateException("no link to upload to: the request was sent again with its key");
}

HttpRequest.Builder put =
    HttpRequest.newBuilder(URI.create(upload.getUrl()))
        .PUT(HttpRequest.BodyPublishers.ofByteArray(pdf));
upload.getHeaders().forEach(put::header);
HttpResponse<Void> uploaded =
    HttpClient.newHttpClient().send(put.build(), HttpResponse.BodyHandlers.discarding());
if (uploaded.statusCode() / 100 != 2) {
  throw new IOException("the upload was refused: " + uploaded.statusCode());
}

drafts.completeDocument(completeKey, added.getDocument().getId());
```

`addKey` and `completeKey` are keys of your own, one for each request, as the next section says. The
upload goes with no key of yours: the client puts your key on the SDK's own requests alone, never on
another `HttpClient`.

## Retries and `Idempotency-Key`

`addDocument`, `completeDocument`, `sendDraft` and `registerWebhookEndpoint` take an
`Idempotency-Key` of your own, such as `UUID.randomUUID().toString()`, which you keep with the
request. Sent again with the same key within 24 hours, the request is answered as it was the first
time rather than done twice, so a retry after a timeout can't send a fax twice. The SDK makes up no
key itself: one it made would be new on each try, and protect nothing.

The answer to a request sent again keeps none of its signed links, nor an endpoint's secret:
`getUpload()`, a document's `getUrl()` and an endpoint's `getSecret()` come back null. So an upload
that `addDocument` gave, asked for again with its key, has no link to send the bytes to. Take that
document out with `removeDocument`, and add it again with a new key. An endpoint whose first answer
you lost has no secret to check its deliveries by: remove it with `removeWebhookEndpoint`, and
register it again with a new key.

## Webhooks

`registerWebhookEndpoint` registers an endpoint of your server, which every event goes to from then
on: when a fax you sent is delivered or fails for good, and when a fax sent to your number arrives.
Its answer shows the endpoint's signing secret once, so keep it then. `removeWebhookEndpoint` takes
an endpoint away, as revoking the key that registered it does. Both take an API key, not an app's
token.

Check each delivery's `webhook-signature` with that secret, against its body exactly as it came,
before you trust it. [XipFax's webhooks guide](https://developer.xipfax.com/docs/guides/webhooks/)
shows how. Its body is then a `FaxEvent`, which the client's
`getObjectMapper().readValue(body, FaxEvent.class)` reads.

## Versions

Each request names the version of the API the SDK was generated from, `XipFax.API_VERSION`, in its
`Xip-Version` header, so each answer keeps the shape the SDK's types describe. Within that version,
an answer may gain a member, and a set of values a new value: the SDK reads both, and keeps a member
it doesn't know in the model's `getAdditionalProperties()`.

## Where the SDK comes from

`openapi.json` is the API's document, as [api.xipfax.com](https://api.xipfax.com/openapi.json)
gives it, and `scripts/generate.sh` makes the SDK from it with
[openapi-generator](https://openapi-generator.tech) 7.17.0's Java generator, its `native` library,
and `openapi-generator.yaml`. It first writes the document the SDK is generated from, by
`scripts/sdk-document.jq`, which sets aside what the SDK sends itself or doesn't offer: the
document's security, since `XipFax` sends your key on every request, and an app's token is not the
SDK's; the per-request `Xip-Version`, since `XipFax` names the version on every request; and the
webhooks, which are XipFax's requests to your server, their bodies typed as `FaxEvent`. Then it
writes `ApiVersion.java` from the document's version. Nothing the generator writes is edited by
hand: `XipFax.java` is the hand-written part. To update the SDK, copy the new document over
`openapi.json`, run `scripts/generate.sh`, and commit what changes. `scripts/gates.sh` checks that
the SDK is what the document generates, then builds it with its specs and the quickstart, packs it
with its sources and its Javadoc, and runs the quickstart with no key; `scripts/test.sh` runs the
specs, which walk the quickstart against a stand-in of the API. Both run Maven offline, after
`scripts/fetch.sh` has fetched what the build needs from Maven Central.

## Help

Write to [help@xipfax.com](mailto:help@xipfax.com).

## License

[MIT](https://github.com/XipHubHQ/xipfax-java/blob/main/LICENSE).
