package ru.r2cloud.bme;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

public class HttpResponse implements HttpHandler {

	private final int statusCode;
	private final String message;

	private String requestBody;
	private int executedTimes;
	private String userAgent;

	public HttpResponse(int statusCode, String message) {
		this.statusCode = statusCode;
		this.message = message;
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		userAgent = exchange.getRequestHeaders().getFirst("User-Agent");
		requestBody = convertToString(exchange.getRequestBody());
		executedTimes++;
		byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(statusCode, bytes.length);
		OutputStream os = exchange.getResponseBody();
		os.write(bytes);
		os.close();
	}

	private static String convertToString(InputStream is) {
		try (java.util.Scanner s = new java.util.Scanner(is)) {
			return s.useDelimiter("\\A").hasNext() ? s.next() : "";
		}
	}
	
	public int getExecutedTimes() {
		return executedTimes;
	}

	public String getRequestBody() {
		return requestBody;
	}

	public String getMessage() {
		return message;
	}

	public int getStatusCode() {
		return statusCode;
	}
	
	public String getUserAgent() {
		return userAgent;
	}
}
