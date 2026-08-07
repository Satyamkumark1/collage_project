package com.studyflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class StudyFlowBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(StudyFlowBackendApplication.class, args);
	}

}
