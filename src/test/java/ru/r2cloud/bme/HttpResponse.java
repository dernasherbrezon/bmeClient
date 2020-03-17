package ru.r2cloud.bme;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

public class HttpResponse implements HttpHandler {

	private final int statusCode;
	private final String message;

	public HttpResponse(int statusCode, String message) {
		this.statusCode = statusCode;
		this.message = message;
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(statusCode, bytes.length);
		OutputStream os = exchange.getResponseBody();
		os.write(bytes);
		os.close();
	}
	
	public String getMessage() {
		return message;
	}
	
	public int getStatusCode() {
		return statusCode;
	}
}
