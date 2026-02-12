package com.mininetflix.ratelimit;

import com.mininetflix.model.User;
import com.mininetflix.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimitService {

    private final VideoRepository videoRepository;

    @Value("${rate-limit.free-tier.uploads-per-day}")
    private int freeTierDailyLimit;

    @Value("${rate-limit.pro-tier.uploads-per-day}")
    private int proTierDailyLimit;

    @Value("${rate-limit.free-tier.max-file-size-mb}")
    private long freeTierMaxFileSizeMb;

    @Value("${rate-limit.pro-tier.max-file-size-mb}")
    private long proTierMaxFileSizeMb;

    /**
     * Check if user can upload.
     * Free tier: 3 uploads/day
     * Pro tier: 50 uploads/day
     */
    public void checkUploadLimit(User user) {
        long todayUploads = videoRepository.countTodayUploads(user.getId());
        int limit = getDailyLimit(user);

        if (todayUploads >= limit) {
            throw new RateLimitExceededException(
                String.format("Daily upload limit reached (%d/%d). Upgrade to Pro for higher limits.",
                        todayUploads, limit)
            );
        }

        log.debug("User {} has {}/{} uploads today", user.getUsername(), todayUploads, limit);
    }

    /**
     * Validate file size against tier limit.
     */
    public void checkFileSizeLimit(User user, long fileSizeBytes) {
        long maxSizeBytes = getMaxFileSizeMb(user) * 1024 * 1024;

        if (fileSizeBytes > maxSizeBytes) {
            throw new RateLimitExceededException(
                String.format("File size %.1f MB exceeds your tier limit of %d MB. Upgrade to Pro.",
                        fileSizeBytes / (1024.0 * 1024.0), getMaxFileSizeMb(user))
            );
        }
    }

    public int getDailyLimit(User user) {
        return user.getTier() == User.UserTier.FREE ? freeTierDailyLimit : proTierDailyLimit;
    }

    public long getMaxFileSizeMb(User user) {
        return user.getTier() == User.UserTier.FREE ? freeTierMaxFileSizeMb : proTierMaxFileSizeMb;
    }

    public static class RateLimitExceededException extends RuntimeException {
        public RateLimitExceededException(String message) {
            super(message);
        }
    }
}
