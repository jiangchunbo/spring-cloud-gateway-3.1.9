package com.atguigu;

import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

public class FluxDemo {
	public static void main(String[] args) {
		SubmissionPublisher<String> publisher = new SubmissionPublisher<>();
		for (int i = 0; i < 10; i++) {
			publisher.submit("Hello World " + i);
		}
	}
}
