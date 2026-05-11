package com.meeting.api.start;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.meeting.api")
public class MeetingApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(MeetingApiApplication.class, args);
    }
}
