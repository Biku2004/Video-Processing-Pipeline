package com.mininetflix;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class VideoStreamingApplication {

    public static void main(String[] args) {
        SpringApplication.run(VideoStreamingApplication.class, args);
    }
}
