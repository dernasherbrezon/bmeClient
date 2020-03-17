package ru.r2cloud.bme;

import java.io.IOException;
import java.util.List;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

public class SequentialHttpResponse implements HttpHandler {

	private final List<HttpResponse> responses;

	private int currentResponse = 0;

	public SequentialHttpResponse(List<HttpResponse> responses) {
		this.responses = responses;
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		if (currentResponse >= responses.size()) {
			throw new IOException();
		}
		HttpResponse response = responses.get(currentResponse);
		currentResponse++;
		response.handle(exchange);
	}
}
