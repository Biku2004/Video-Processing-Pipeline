package com.mininetflix.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.mediaconvert.MediaConvertClient;
import software.amazon.awssdk.services.mediaconvert.model.*;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class MediaConvertService {

    private final MediaConvertClient mediaConvertClient;

    @Value("${aws.s3.input-bucket}")
    private String inputBucket;

    @Value("${aws.s3.output-bucket}")
    private String outputBucket;

    @Value("${aws.mediaconvert.role-arn}")
    private String roleArn;

    @Value("${aws.mediaconvert.output-prefix}")
    private String outputPrefix;

    /**
     * Creates a MediaConvert job that:
     * 1. Splits video into HLS segments (2-6 seconds each)
     * 2. Encodes to multiple resolutions based on input quality
     * 3. Generates adaptive bitrate playlists (ABR/HLS)
     * 
     * This is production-grade - same concept as Netflix's transcoding pipeline.
     */
    public String createTranscodingJob(String videoId, String s3Key, String inputResolution) {
        String inputS3Uri = "s3://" + inputBucket + "/" + s3Key;
        String outputS3Uri = "s3://" + outputBucket + "/" + outputPrefix + videoId + "/";

        // Smart encoding: only create resolutions <= input resolution (cost optimization)
        List<OutputGroup> outputGroups = buildAdaptiveBitrateOutputGroups(
                outputS3Uri, inputResolution
        );

        CreateJobRequest jobRequest = CreateJobRequest.builder()
                .role(roleArn)
                .jobTemplate("")
                .settings(JobSettings.builder()
                        .inputs(Input.builder()
                                .fileInput(inputS3Uri)
                                .audioSelectors(buildAudioSelectors())
                                .build())
                        .outputGroups(outputGroups)
                        .build())
                .userMetadata(java.util.Map.of("videoId", videoId))
                .build();

        try {
            CreateJobResponse response = mediaConvertClient.createJob(jobRequest);
            String jobId = response.job().id();
            log.info("Created MediaConvert job {} for videoId: {}", jobId, videoId);
            return jobId;
        } catch (Exception e) {
            log.error("Failed to create MediaConvert job for videoId {}: {}", videoId, e.getMessage());
            throw new RuntimeException("MediaConvert job creation failed", e);
        }
    }

    /**
     * Check MediaConvert job status for progress tracking.
     */
    public JobStatus getJobStatus(String jobId) {
        try {
            GetJobResponse response = mediaConvertClient.getJob(
                    GetJobRequest.builder().id(jobId).build()
            );
            return response.job().status();
        } catch (Exception e) {
            log.warn("Failed to get job status for {}: {}", jobId, e.getMessage());
            return JobStatus.ERROR;
        }
    }

    /**
     * Get job progress percentage (0-100).
     */
    public int getJobProgress(String jobId) {
        try {
            GetJobResponse response = mediaConvertClient.getJob(
                    GetJobRequest.builder().id(jobId).build()
            );
            return response.job().jobPercentComplete();
        } catch (Exception e) {
            return 0;
        }
    }

    // =================== Private Helpers ===================

    private List<OutputGroup> buildAdaptiveBitrateOutputGroups(
            String outputUri, String inputResolution) {

        // HLS output group with adaptive bitrate (ABR)
        HlsGroupSettings hlsGroupSettings = HlsGroupSettings.builder()
                .destination(outputUri)
                .segmentLength(6)           // 6-second segments (standard)
                .minSegmentLength(0)
                .minFinalSegmentLength(0.0)
                .codecSpecification(HlsCodecSpecification.RFC_4281)
                .directoryStructure(HlsDirectoryStructure.SINGLE_DIRECTORY)
                .manifestDurationFormat(HlsManifestDurationFormat.INTEGER)
                .outputSelection(HlsOutputSelection.MANIFESTS_AND_SEGMENTS)
                .programDateTime(HlsProgramDateTime.EXCLUDE)
                .build();

        // Determine which resolutions to encode based on input
        List<Output> outputs = buildResolutionOutputs(inputResolution);

        return List.of(
                OutputGroup.builder()
                        .name("HLS Group")
                        .outputGroupSettings(OutputGroupSettings.builder()
                                .type(OutputGroupType.HLS_GROUP_SETTINGS)
                                .hlsGroupSettings(hlsGroupSettings)
                                .build())
                        .outputs(outputs)
                        .build()
        );
    }

    private List<Output> buildResolutionOutputs(String inputResolution) {
        // Smart: only generate resolutions <= input resolution
        boolean is1080p = is1080pOrHigher(inputResolution);
        boolean is720p = is720pOrHigher(inputResolution);

        var outputs = new java.util.ArrayList<Output>();

        if (is1080p) {
            outputs.add(buildVideoOutput("1080p", 1920, 1080, 5_000_000, "00000001.m3u8"));
        }
        if (is720p) {
            outputs.add(buildVideoOutput("720p", 1280, 720, 2_800_000, "00000002.m3u8"));
        }
        // Always generate 480p as minimum quality
        outputs.add(buildVideoOutput("480p", 854, 480, 1_400_000, "00000003.m3u8"));

        return outputs;
    }

    private Output buildVideoOutput(String nameModifier, int width, int height,
                                     int bitrate, String nameModifierSuffix) {
        return Output.builder()
                .nameModifier("_" + nameModifier)
                .containerSettings(ContainerSettings.builder()
                        .container(ContainerType.M3_U8)
                        .build())
                .videoDescription(VideoDescription.builder()
                        .width(width)
                        .height(height)
                        .codecSettings(VideoCodecSettings.builder()
                                .codec(VideoCodec.H_264)
                                .h264Settings(H264Settings.builder()
                                        .bitrate(bitrate)
                                        .rateControlMode(H264RateControlMode.CBR)
                                        .codecProfile(H264CodecProfile.MAIN)
                                        .codecLevel(H264CodecLevel.AUTO)
                                        .framerateControl(H264FramerateControl.INITIALIZE_FROM_SOURCE)
                                        .gopSize(90.0)
                                        .gopSizeUnits(H264GopSizeUnits.FRAMES)
                                        .numberBFramesBetweenReferenceFrames(2)
                                        .entropyEncoding(H264EntropyEncoding.CABAC)
                                        .build())
                                .build())
                        .build())
                .audioDescriptions(buildAudioDescription())
                .build();
    }

    private List<AudioDescription> buildAudioDescription() {
        return List.of(
                AudioDescription.builder()
                        .audioSourceName("Audio Selector 1")
                        .codecSettings(AudioCodecSettings.builder()
                                .codec(AudioCodec.AAC)
                                .aacSettings(AacSettings.builder()
                                        .bitrate(128000)
                                        .codingMode(AacCodingMode.CODING_MODE_2_0)
                                        .sampleRate(48000)
                                        .build())
                                .build())
                        .build()
        );
    }

    private java.util.Map<String, AudioSelector> buildAudioSelectors() {
        return java.util.Map.of(
                "Audio Selector 1",
                AudioSelector.builder()
                        .defaultSelection(AudioDefaultSelection.DEFAULT)
                        .build()
        );
    }

    private boolean is1080pOrHigher(String resolution) {
        if (resolution == null) return false;
        try {
            String[] parts = resolution.split("x");
            return Integer.parseInt(parts[1]) >= 1080;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean is720pOrHigher(String resolution) {
        if (resolution == null) return false;
        try {
            String[] parts = resolution.split("x");
            return Integer.parseInt(parts[1]) >= 720;
        } catch (Exception e) {
            return true; // default to generating 720p
        }
    }
}
