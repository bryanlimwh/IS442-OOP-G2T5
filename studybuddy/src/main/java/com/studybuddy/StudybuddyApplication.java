package com.studybuddy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;


@SpringBootApplication
public class StudybuddyApplication {

	public static void main(String[] args) {
		// Scans the package containing this class and all its subpackages for classes marked
		// with annotations such as @Controller, @Service, @Repository and @Component
		SpringApplication.run(StudybuddyApplication.class, args);
	}

}
