package org.springframework.cloud.gateway.sample;

import io.netty.buffer.ByteBuf;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientResponse;

import java.util.function.Consumer;

public class ReactorHttpClientDemo {
	public static void main(String[] args) {
//		HttpClient client = HttpClient.create();

//		client.get()
//				.uri("https://example.com/")
//				// 从给定的 HTTP 端点接受数据 → ByteBufFlux
//				.responseContent()
//				// 聚合数据 → ByteBufMono
//				.aggregate()
//				// 转换为字符串
//				.asString()
//				.block();

//HttpClient client = HttpClient.create()
//		.host("www.baidu.com")
//		.port(80);
//
//client.get()
//		.uri("/")
//		.response()
//		.block();

//HttpClient client = HttpClient.create();
//
//client.warmup()
//		.block();
//
//client.post()
//		.uri("https://example.com/")
//		.send(ByteBufFlux.fromString(Mono.just("hello")))
//		.response()
//		.block();


		HttpClient client = HttpClient.create();

		client.post()
				.uri("https://example.com/")
				.send(ByteBufFlux.fromString(Mono.just("hello")))
				.response()
				.block();
	}
}
