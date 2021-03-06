package ru.r2cloud.bme;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpRequest.Builder;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

public class BmeClient {

	private static final int HEX_0X0F = 0x0F;
	private static final Logger LOG = LoggerFactory.getLogger(BmeClient.class);
	private static final long TOKEN_EXPIRATION_MILLIS = Duration.ofHours(1).toMillis();
	private static final int RETRIES = 3;

	private final String host;
	private final int port;
	private final String username;
	private final String password;
	private final long retryTimeoutMillis;
	private final int timeout;

	private static String userAgent;

	private String authToken;
	private HttpClient httpclient;
	private long validUntil;

	static {
		String version = readVersion();
		if (version == null) {
			version = "1.1";
		}
		userAgent = "bmeClient/" + version + " (dernasherbrezon)";
	}

	public BmeClient(String host, int port, int timeout, long retryTimeoutMillis, String username, String password) {
		this.host = host;
		this.port = port;
		this.username = username;
		this.password = password;
		this.timeout = timeout;
		this.retryTimeoutMillis = retryTimeoutMillis;
		this.httpclient = HttpClient.newBuilder().version(Version.HTTP_2).followRedirects(Redirect.NORMAL).connectTimeout(Duration.ofMillis(timeout)).build();
	}

	public void uploadBatch(Satellite satellite, List<byte[]> packets) throws IOException, AuthenticationException {
		if (satellite == null || packets == null) {
			throw new IllegalArgumentException();
		}
		if (LOG.isTraceEnabled()) {
			LOG.trace("uploading: {} number of packets: {}", satellite, packets.size());
		}
		if (packets.isEmpty()) {
			return;
		}
		int maxBatchSize = 30;
		for (int i = 0; i < packets.size(); i += maxBatchSize) {
			uploadBatchWithRetry(satellite, packets, i, maxBatchSize);
		}
	}

	private void uploadBatchWithRetry(Satellite satellite, List<byte[]> packets, int offset, int length) throws AuthenticationException, IOException {
		int currentRetry = 0;
		while (!Thread.currentThread().isInterrupted()) {
			try {
				refreshToken();

				if (!uploadBatchWithoutRetry(satellite, packets, offset, length)) {
					continue;
				}

				break;

			} catch (IOException e) {
				if (currentRetry < RETRIES) {
					currentRetry++;
					LOG.info("unable to upload: {} retry...{} exception {}", satellite, currentRetry, e.getMessage());
					try {
						Thread.sleep(retryTimeoutMillis);
					} catch (InterruptedException e1) {
						LOG.info("sleep interrupted. exit");
						Thread.currentThread().interrupt();
						break;
					}
					continue;
				}
				throw e;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
	}

	private boolean uploadBatchWithoutRetry(Satellite satellite, List<byte[]> packets, int offset, int length) throws IOException, InterruptedException {
		String requestBody = convert(satellite, packets, offset, length);
		if (LOG.isDebugEnabled()) {
			LOG.debug("request: {}", requestBody);
		}
		Builder result = HttpRequest.newBuilder().uri(URI.create(host + ":" + port + "/api/packets/bulk"));
		result.timeout(Duration.ofMillis(timeout));
		result.header("User-Agent", userAgent);
		result.header("Content-Type", "application/json");
		result.header("Authorization", "Bearer " + authToken);
		result.POST(BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));

		HttpRequest request = result.build();
		HttpResponse<String> response = httpclient.send(request, BodyHandlers.ofString());
		if (LOG.isDebugEnabled()) {
			LOG.debug("response: {}", response.body());
		}
		if (response.statusCode() == 401) {
			authToken = null;
			LOG.info("token expired. retry");
			return false;
		}

		if (response.statusCode() == 400) {
			if (LOG.isErrorEnabled()) {
				LOG.error("invalid request. response: {}", response.body());
			}
			// do not retry
			return true;
		}

		if (response.statusCode() != 200) {
			// trigger retry
			throw new IOException("invalid response code: " + response.statusCode());
		}

		return true;
	}

	private static String convert(Satellite satellite, List<byte[]> packets, int offset, int length) {
		JsonArray array = new JsonArray();
		for (int i = offset; i < packets.size() && i < (offset + length); i++) {
			byte[] cur = packets.get(i);
			JsonObject curObject = new JsonObject();
			curObject.add("satellite", satellite.name().toLowerCase(Locale.UK));
			curObject.add("packet", convertToHex(cur));
			array.add(curObject);
		}
		JsonObject result = new JsonObject();
		result.add("packets", array);
		return result.toString();
	}

	private void refreshToken() throws InterruptedException, IOException, AuthenticationException {
		if (authToken != null && System.currentTimeMillis() < validUntil) {
			return;
		}
		if (authToken != null) {
			LOG.info("re-newing auth token");
		}
		long start = System.currentTimeMillis();
		Builder result = HttpRequest.newBuilder().uri(URI.create(host + ":" + port + "/api/tokens"));
		result.timeout(Duration.ofMillis(timeout));
		result.header("User-Agent", userAgent);
		result.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes()));
		result.POST(BodyPublishers.noBody());

		HttpResponse<String> response = httpclient.send(result.build(), BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			if (response.statusCode() >= 500) {
				// this might be temporary
				// allow retry algorithm to kick off
				throw new IOException("unable to authenticate: " + response.statusCode());
			} else {
				// this is permanent. no need to retry
				throw new AuthenticationException("unable to authenticate");
			}
		}
		authToken = parseToken(response.body());
		validUntil = (start + TOKEN_EXPIRATION_MILLIS) - timeout;
		LOG.info("the token will expire at: {}", new Date(validUntil));
	}

	private static String parseToken(String body) throws IOException {
		JsonValue value = Json.parse(body);
		if (!value.isObject()) {
			LOG.error("invalid body: {}", body);
			throw new IOException("invalid response");
		}
		JsonObject object = value.asObject();
		String result = object.getString("token", null);
		if (result == null) {
			LOG.error("invalid body: {}", body);
			throw new IOException("invalid response");
		}
		return result;
	}

	private static String convertToHex(final byte[] data) {
		final StringBuilder buf = new StringBuilder();
		for (final byte element : data) {
			int halfbyte = (element >>> 4) & HEX_0X0F;
			int twoHalfs = 0;
			do {
				if (0 <= halfbyte && halfbyte <= 9) {
					buf.append((char) ('0' + halfbyte));
				} else {
					buf.append((char) ('a' + (halfbyte - 10)));
				}
				halfbyte = element & HEX_0X0F;
			} while (twoHalfs++ < 1);
		}
		return buf.toString();
	}

	private static String readVersion() {
		try (InputStream is = BmeClient.class.getClassLoader().getResourceAsStream("META-INF/maven/ru.r2cloud/bmeClient/pom.properties")) {
			if (is != null) {
				Properties p = new Properties();
				p.load(is);
				return p.getProperty("version", null);
			}
			return null;
		} catch (IOException e) {
			return null;
		}
	}
}
