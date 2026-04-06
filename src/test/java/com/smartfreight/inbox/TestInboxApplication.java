package com.smartfreight.inbox;

import org.springframework.boot.SpringApplication;

public class TestInboxApplication {

	public static void main(String[] args) {
		SpringApplication.from(InboxApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
