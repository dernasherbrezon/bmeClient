package ru.r2cloud.bme;

import static org.junit.Assert.assertEquals;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonValue;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public class BmeClientTest {

	private HttpServer server;
	private BmeClient client;
	private HttpResponse bulkHandler;

	@Test
	public void testSuccess() throws Exception {
		assertSuccess();
	}

	@Test(expected = AuthenticationException.class)
	public void testAuthFailure() throws Exception {
		setupContext("/api/tokens", new HttpResponse(401, "{\"error\":\"Unauthorized\"}"));
		client.uploadBatch(Satellite.SMOGP, Collections.singletonList(new byte[] { (byte) 0xca, (byte) 0xfe }));
	}

	@Test
	public void testRetryAuthOnInternalSystemError() throws Exception {
		List<HttpResponse> responses = new ArrayList<>();
		responses.add(new HttpResponse(503, "{\"error\":\"internal server error\"}"));
		responses.add(new HttpResponse(200, "{\"token\":\"1234567890\"}"));
		setupContext("/api/tokens", new SequentialHttpResponse(responses));
		assertSuccess();
	}

	@Test
	public void testExpireTokenWhileSendingRequest() throws Exception {
		List<HttpResponse> responses = new ArrayList<>();
		responses.add(new HttpResponse(401, "{\"error\":\"Unauthorized\"}"));
		responses.add(bulkHandler);
		setupContext("/api/packets/bulk", new SequentialHttpResponse(responses));
		assertSuccess();
	}

	@Test
	public void testDoNotRetryOn400() throws Exception {
		setupContext("/api/packets/bulk", new HttpResponse(400, "{\"error\":\"Bad request\"}"));
		client.uploadBatch(Satellite.SMOGP, Collections.singletonList(new byte[] { (byte) 0x01, (byte) 0xfe }));
		assertEquals(0, bulkHandler.getExecutedTimes());
	}

	@Test
	public void testRetryOnInternalSystemError() throws Exception {
		List<HttpResponse> responses = new ArrayList<>();
		responses.add(new HttpResponse(503, "{\"error\":\"internal server error\"}"));
		responses.add(bulkHandler);
		setupContext("/api/packets/bulk", new SequentialHttpResponse(responses));
		assertSuccess();
	}

	@Test
	public void testNoPackets() throws Exception {
		client.uploadBatch(Satellite.SMOGP, Collections.emptyList());
		assertEquals(0, bulkHandler.getExecutedTimes());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testParameters() throws Exception {
		client.uploadBatch(null, Collections.emptyList());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testParameters2() throws Exception {
		client.uploadBatch(Satellite.SMOGP, null);
	}

	@Test
	public void testSplitBatch() throws Exception {
		HttpResponse response1 = new HttpResponse(200, "{\"results\":[{\"location\":\"/api/packets/1234567890\"}]}");
		HttpResponse response2 = new HttpResponse(200, "{\"results\":[{\"location\":\"/api/packets/1234567891\"}]}");
		List<HttpResponse> responses = new ArrayList<>();
		responses.add(response1);
		responses.add(response2);
		setupContext("/api/packets/bulk", new SequentialHttpResponse(responses));

		List<byte[]> batch = new ArrayList<>();
		for (int i = 0; i < 32; i++) {
			batch.add(new byte[] { (byte) (i + 1) });
		}
		client.uploadBatch(Satellite.SMOGP, batch);

		assertJson("expectedBatch1.json", response1.getRequestBody());
		assertJson("expectedBatch2.json", response2.getRequestBody());
	}

	@Before
	public void start() throws Exception {
		String host = "localhost";
		int port = 8000;
		server = HttpServer.create(new InetSocketAddress(host, port), 0);
		server.start();
		server.createContext("/api/tokens", new HttpResponse(200, "{\"token\":\"1234567890\"}"));
		bulkHandler = new HttpResponse(200, "{\"results\":[{\"location\":\"/api/packets/1234567890\"}]}");
		server.createContext("/api/packets/bulk", bulkHandler);
		client = new BmeClient("http://" + host, port, 10000, 0L, UUID.randomUUID().toString(), UUID.randomUUID().toString());
	}

	private void assertSuccess() throws IOException, AuthenticationException {
		client.uploadBatch(Satellite.SMOGP, Collections.singletonList(new byte[] { (byte) 0xca, (byte) 0xfe }));
		assertEquals("{\"packets\":[{\"satellite\":\"smogp\",\"packet\":\"cafe\"}]}", bulkHandler.getRequestBody());
		assertEquals("bmeClient/test-1.0 (dernasherbrezon)", bulkHandler.getUserAgent());
	}

	private void setupContext(String name, HttpHandler handler) {
		server.removeContext(name);
		server.createContext(name, handler);
	}

	@After
	public void stop() {
		if (server != null) {
			server.stop(0);
		}
	}

	private static void assertJson(String classpath, String actual) throws Exception {
		try (BufferedReader r = new BufferedReader(new InputStreamReader(BmeClientTest.class.getClassLoader().getResourceAsStream(classpath), StandardCharsets.UTF_8))) {
			JsonValue expected = Json.parse(r);
			StringWriter w = new StringWriter();
			expected.writeTo(w);
			assertEquals(w.toString(), actual);
		}
	}

}
