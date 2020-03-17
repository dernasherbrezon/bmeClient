package ru.r2cloud.bme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

public class BmeClientTest {

	private HttpServer server;
	private BmeClient client;

	private String request;

	@Test
	public void testSuccess() throws Exception {
		assertSuccess();
	}

	@Test(expected = AuthenticationException.class)
	public void testAuthFailure() throws Exception {
		server.removeContext("/api/tokens");
		server.createContext("/api/tokens", new HttpResponse(401, "{\"error\":\"Unauthorized\"}"));
		client.uploadBatch(Satellite.SMOGP, Collections.singletonList(new byte[] { (byte) 0xca, (byte) 0xfe }));
	}

	@Test
	public void testRetryAuthOnInternalSystemError() throws Exception {
		server.removeContext("/api/tokens");
		List<HttpResponse> responses = new ArrayList<>();
		responses.add(new HttpResponse(503, "{\"error\":\"internal server error\"}"));
		responses.add(new HttpResponse(200, "{\"token\":\"1234567890\"}"));
		server.createContext("/api/tokens", new SequentialHttpResponse(responses));
		assertSuccess();
	}

	@Test
	public void testExpireTokenWhileSendingRequest() throws Exception {
		server.removeContext("/api/packets/bulk");
		List<HttpResponse> responses = new ArrayList<>();
		responses.add(new HttpResponse(401, "{\"error\":\"Unauthorized\"}"));
		responses.add(createSuccessBulkHandler());
		server.createContext("/api/packets/bulk", new SequentialHttpResponse(responses));
		assertSuccess();
	}

	@Test
	public void testRetryOnInternalSystemError() throws Exception {
		server.removeContext("/api/packets/bulk");
		List<HttpResponse> responses = new ArrayList<>();
		responses.add(new HttpResponse(503, "{\"error\":\"internal server error\"}"));
		responses.add(createSuccessBulkHandler());
		server.createContext("/api/packets/bulk", new SequentialHttpResponse(responses));
		assertSuccess();
	}

	@Test
	public void testNoPackets() throws Exception {
		client.uploadBatch(Satellite.SMOGP, Collections.emptyList());
		assertNull(request);
	}

	@Before
	public void start() throws Exception {
		request = null;
		String host = "localhost";
		int port = 8000;
		server = HttpServer.create(new InetSocketAddress(host, port), 0);
		server.start();
		server.createContext("/api/tokens", new HttpResponse(200, "{\"token\":\"1234567890\"}"));
		server.createContext("/api/packets/bulk", createSuccessBulkHandler());
		client = new BmeClient("http://" + host, port, 10000, 0L, UUID.randomUUID().toString(), UUID.randomUUID().toString());
	}

	private HttpResponse createSuccessBulkHandler() {
		return new HttpResponse(200, "{\"results\":[{\"location\":\"/api/packets/1234567890\"}]}") {

			@Override
			public void handle(HttpExchange exchange) throws IOException {
				request = convertToString(exchange.getRequestBody());
				byte[] bytes = getMessage().getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(getStatusCode(), bytes.length);
				OutputStream os = exchange.getResponseBody();
				os.write(bytes);
				os.close();
			}
		};
	}

	private void assertSuccess() throws IOException, AuthenticationException {
		client.uploadBatch(Satellite.SMOGP, Collections.singletonList(new byte[] { (byte) 0xca, (byte) 0xfe }));
		assertEquals("{\"packets\":[{\"satellite\":\"smogp\",\"packet\":\"cafe\"}]}", request);
	}

	private static String convertToString(InputStream is) {
		try (java.util.Scanner s = new java.util.Scanner(is)) {
			return s.useDelimiter("\\A").hasNext() ? s.next() : "";
		}
	}

	@After
	public void stop() {
		if (server != null) {
			server.stop(0);
		}
	}

}
