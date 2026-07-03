package com.godoc.consult;

import org.springframework.boot.SpringApplication;

public class TestConsultApplication {

	public static void main(String[] args) {
		SpringApplication.from(ConsultApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
