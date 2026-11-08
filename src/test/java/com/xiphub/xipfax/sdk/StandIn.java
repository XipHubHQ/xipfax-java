package com.xiphub.xipfax.sdk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

/**
 * Answers the SDK's requests in place of the API, in the specs: the HttpClient a client is given by
 * {@link ApiClient#setHttpClientBuilder}, which keeps each request it is sent, with its body, and
 * answers it as its answer says, in the spec's own process. Nothing it does reaches a network.
 */
final class StandIn {
  /** The status and the JSON body the stand-in answers a request with; an empty body answers none. */
  static final class Answer {
    final int status;
    final String body;

    Answer(int status, String body) {
      this.status = status;
      this.body = body;
    }
  }

  private final Function<SentRequest, Answer> answer;
  private final List<SentRequest> requests = new ArrayList<>();

  StandIn(Function<SentRequest, Answer> answer) {
    this.answer = answer;
  }

  /** A stand-in that answers every request with one status and one body. */
  static StandIn same(int status, String body) {
    return new StandIn(request -> new Answer(status, body));
  }

  /** A builder of HttpClients whose every request goes to the stand-in. */
  HttpClient.Builder builder() {
    return new Builder();
  }

  /** Each request the stand-in was given, in its order. */
  synchronized List<SentRequest> requests() {
    return new ArrayList<>(requests);
  }

  private synchronized void keep(SentRequest request) {
    requests.add(request);
  }

  /** The request as the stand-in keeps it, its body read from what the SDK publishes. */
  private static SentRequest keptOf(HttpRequest request) throws IOException, InterruptedException {
    byte[] body = new byte[0];
    Optional<HttpRequest.BodyPublisher> publisher = request.bodyPublisher();
    if (publisher.isPresent()) {
      CompletableFuture<byte[]> read = new CompletableFuture<>();
      publisher.get().subscribe(new BodyReader(read));
      try {
        body = read.get(10, TimeUnit.SECONDS);
      } catch (ExecutionException failed) {
        throw new IOException("the request's body couldn't be read", failed.getCause());
      } catch (TimeoutException stalled) {
        throw new IOException("the request's body wasn't published within 10 seconds", stalled);
      }
    }
    return new SentRequest(request.method(), request.uri(), request.headers().map(), body);
  }

  /** Reads a request's body, each part of it as it is published, until it ends. */
  private static final class BodyReader implements Flow.Subscriber<ByteBuffer> {
    private final CompletableFuture<byte[]> read;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    BodyReader(CompletableFuture<byte[]> read) {
      this.read = read;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(ByteBuffer part) {
      byte[] chunk = new byte[part.remaining()];
      part.get(chunk);
      bytes.write(chunk, 0, chunk.length);
    }

    @Override
    public void onError(Throwable failure) {
      read.completeExceptionally(failure);
    }

    @Override
    public void onComplete() {
      read.complete(bytes.toByteArray());
    }
  }

  /** Gives an answer's body to the subscriber a response's handler makes, then ends it. */
  private static final class Feed implements Flow.Subscription {
    private final Flow.Subscriber<? super List<ByteBuffer>> subscriber;
    private final byte[] body;
    private boolean given;
    private boolean ended;

    Feed(Flow.Subscriber<? super List<ByteBuffer>> subscriber, byte[] body) {
      this.subscriber = subscriber;
      this.body = body;
    }

    @Override
    public synchronized void request(long count) {
      if (ended) {
        return;
      }
      if (count <= 0) {
        ended = true;
        subscriber.onError(new IllegalArgumentException("a request for " + count + " parts"));
        return;
      }
      if (!given && body.length > 0) {
        given = true;
        subscriber.onNext(List.of(ByteBuffer.wrap(body)));
      }
      if (!ended) {
        ended = true;
        subscriber.onComplete();
      }
    }

    @Override
    public synchronized void cancel() {
      ended = true;
    }
  }

  /** What a response's handler is told of it before its body. */
  private static final class Info implements HttpResponse.ResponseInfo {
    private final int status;
    private final HttpHeaders headers;

    Info(int status, HttpHeaders headers) {
      this.status = status;
      this.headers = headers;
    }

    @Override
    public int statusCode() {
      return status;
    }

    @Override
    public HttpHeaders headers() {
      return headers;
    }

    @Override
    public HttpClient.Version version() {
      return HttpClient.Version.HTTP_1_1;
    }
  }

  /** The stand-in's answer to a request, with the body its handler made of it. */
  private static final class Response<T> implements HttpResponse<T> {
    private final HttpRequest request;
    private final Info info;
    private final T body;

    Response(HttpRequest request, Info info, T body) {
      this.request = request;
      this.info = info;
      this.body = body;
    }

    @Override
    public int statusCode() {
      return info.statusCode();
    }

    @Override
    public HttpRequest request() {
      return request;
    }

    @Override
    public Optional<HttpResponse<T>> previousResponse() {
      return Optional.empty();
    }

    @Override
    public HttpHeaders headers() {
      return info.headers();
    }

    @Override
    public T body() {
      return body;
    }

    @Override
    public Optional<SSLSession> sslSession() {
      return Optional.empty();
    }

    @Override
    public URI uri() {
      return request.uri();
    }

    @Override
    public HttpClient.Version version() {
      return info.version();
    }
  }

  /** The HttpClient the SDK sends through, which answers in place of the API. */
  private final class Client extends HttpClient {
    private final Optional<Duration> connectTimeout;
    private final Redirect redirect;

    Client(Optional<Duration> connectTimeout, Redirect redirect) {
      this.connectTimeout = connectTimeout;
      this.redirect = redirect;
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
        throws IOException, InterruptedException {
      SentRequest kept = keptOf(request);
      keep(kept);
      Answer answered = answer.apply(kept);
      byte[] bytes = answered.body.getBytes(StandardCharsets.UTF_8);
      Map<String, List<String>> headers =
          bytes.length > 0
              ? Map.of("Content-Type", List.of("application/json"))
              : Collections.emptyMap();
      Info info = new Info(answered.status, HttpHeaders.of(headers, (name, value) -> true));
      HttpResponse.BodySubscriber<T> subscriber = handler.apply(info);
      subscriber.onSubscribe(new Feed(subscriber, bytes));
      try {
        T body = subscriber.getBody().toCompletableFuture().get(10, TimeUnit.SECONDS);
        return new Response<>(request, info, body);
      } catch (ExecutionException failed) {
        throw new IOException("the answer's body couldn't be read", failed.getCause());
      } catch (TimeoutException stalled) {
        throw new IOException("the answer's body wasn't read within 10 seconds", stalled);
      }
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      try {
        return CompletableFuture.completedFuture(send(request, handler));
      } catch (IOException failed) {
        return CompletableFuture.failedFuture(failed);
      } catch (InterruptedException stopped) {
        Thread.currentThread().interrupt();
        return CompletableFuture.failedFuture(stopped);
      }
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> handler,
        HttpResponse.PushPromiseHandler<T> pushes) {
      // The stand-in answers over no connection, so it pushes nothing.
      return sendAsync(request, handler);
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
      return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
      return connectTimeout;
    }

    @Override
    public Redirect followRedirects() {
      return redirect;
    }

    @Override
    public Optional<ProxySelector> proxy() {
      return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
      try {
        return SSLContext.getDefault();
      } catch (NoSuchAlgorithmException none) {
        throw new IllegalStateException("this Java has no default SSLContext", none);
      }
    }

    @Override
    public SSLParameters sslParameters() {
      return sslContext().getDefaultSSLParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
      return Optional.empty();
    }

    @Override
    public Version version() {
      return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
      return Optional.empty();
    }
  }

  /**
   * The builder the SDK is given, which keeps the connect timeout and the redirects it sets, as the
   * client it builds reports them; the rest of what it's set concerns a connection, and the stand-in
   * makes none.
   */
  private final class Builder implements HttpClient.Builder {
    private Optional<Duration> connectTimeout = Optional.empty();
    private HttpClient.Redirect redirect = HttpClient.Redirect.NEVER;

    @Override
    public HttpClient.Builder cookieHandler(CookieHandler handler) {
      return this;
    }

    @Override
    public HttpClient.Builder connectTimeout(Duration timeout) {
      connectTimeout = Optional.of(timeout);
      return this;
    }

    @Override
    public HttpClient.Builder sslContext(SSLContext context) {
      return this;
    }

    @Override
    public HttpClient.Builder sslParameters(SSLParameters parameters) {
      return this;
    }

    @Override
    public HttpClient.Builder executor(Executor executor) {
      return this;
    }

    @Override
    public HttpClient.Builder followRedirects(HttpClient.Redirect policy) {
      redirect = policy;
      return this;
    }

    @Override
    public HttpClient.Builder version(HttpClient.Version version) {
      return this;
    }

    @Override
    public HttpClient.Builder priority(int priority) {
      return this;
    }

    @Override
    public HttpClient.Builder proxy(ProxySelector selector) {
      return this;
    }

    @Override
    public HttpClient.Builder authenticator(Authenticator authenticator) {
      return this;
    }

    @Override
    public HttpClient build() {
      return new Client(connectTimeout, redirect);
    }
  }
}
