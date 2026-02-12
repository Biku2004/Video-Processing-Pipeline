package com.mininetflix.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class SqsService {

    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;

    @Value("${aws.sqs.queue-url}")
    private String queueUrl;

    /**
     * Send a video processing job to SQS.
     * SQS decouples upload from processing - prevents overload, allows retries.
     */
    public void sendVideoProcessingJob(String videoId, String s3Key,
                                        String userId, String inputBucket) {
        try {
            Map<String, Object> message = new HashMap<>();
            message.put("videoId", videoId);
            message.put("s3Key", s3Key);
            message.put("userId", userId);
            message.put("inputBucket", inputBucket);
            message.put("timestamp", System.currentTimeMillis());

            String messageBody = objectMapper.writeValueAsString(message);

            SendMessageRequest request = SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(messageBody)
                    // Use videoId as deduplication key for FIFO queues
                    .messageGroupId("video-processing")
                    .messageDeduplicationId(videoId)
                    .build();

            sqsClient.sendMessage(request);
            log.info("Queued video processing job for videoId: {}", videoId);

        } catch (Exception e) {
            log.error("Failed to queue video processing job for videoId {}: {}", videoId, e.getMessage());
            throw new RuntimeException("Failed to queue video processing job", e);
        }
    }
}
