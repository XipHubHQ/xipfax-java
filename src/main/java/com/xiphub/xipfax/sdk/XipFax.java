package com.xiphub.xipfax.sdk;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpRequest;
import java.util.function.Consumer;

/**
 * A client of XipFax's API, made from your API key: each request it sends carries the key as a
 * bearer token and names {@link #API_VERSION} in its Xip-Version header. Make each API from it, as
 * in {@code new FaxesApi(new XipFax(apiKey))}. An API takes the client's settings when it is made,
 * so set any before you make one.
 */
public final class XipFax extends ApiClient {
  /** The version of XipFax's API this SDK is generated from, which every request names. */
  public static final String API_VERSION = ApiVersion.API_VERSION;

  /** What each request carries last: your key as a bearer token, and the version. */
  private final Consumer<HttpRequest.Builder> credentials;

  /**
   * Makes a client of XipFax's API at https://api.xipfax.com from your API key, xipfax_key_live_
   * and 32 more characters, which you make in the developer console and keep on your server.
   *
   * @param apiKey your API key
   * @throws IllegalArgumentException if the key is null, empty or only spaces
   */
  public XipFax(String apiKey) {
    this(apiKey, null);
  }

  /**
   * Makes a client of XipFax's API from your API key, at the address you name.
   *
   * @param apiKey your API key
   * @param baseUrl where the API answers, an http or https address with a host, or null for
   *     https://api.xipfax.com
   * @throws IllegalArgumentException if the key is null, empty or only spaces, or the address isn't
   *     an http or https one with a host
   */
  public XipFax(String apiKey, String baseUrl) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalArgumentException(
          "no API key: make one in the developer console, https://developer.xipfax.com/console/");
    }
    String authorization = "Bearer " + apiKey;
    credentials =
        request ->
            request.setHeader("Authorization", authorization).setHeader("Xip-Version", API_VERSION);
    super.setRequestInterceptor(credentials);
    if (baseUrl != null) {
      updateBaseUri(addressOf(baseUrl));
    }
  }

  /**
   * The address each path is sent to the end of: an http or https address with a host, its last
   * slash dropped.
   *
   * @param baseUrl the address named
   * @return the address, with no slash at its end
   * @throws IllegalArgumentException if it isn't an http or https address with a host
   */
  private static String addressOf(String baseUrl) {
    URI address;
    try {
      address = new URI(baseUrl);
    } catch (URISyntaxException notOne) {
      throw new IllegalArgumentException("no address: " + baseUrl, notOne);
    }
    String scheme = address.getScheme();
    if (address.getHost() == null
        || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
      throw new IllegalArgumentException("not an http or https address with a host: " + baseUrl);
    }
    return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
  }

  /**
   * Sets a step each request goes through before it is sent. The client names your key and the
   * version after the step, so that the request always carries its own.
   *
   * @param interceptor the step, or null to take away the one set before
   * @return this client
   */
  @Override
  public XipFax setRequestInterceptor(Consumer<HttpRequest.Builder> interceptor) {
    super.setRequestInterceptor(
        interceptor == null ? credentials : interceptor.andThen(credentials));
    return this;
  }
}
