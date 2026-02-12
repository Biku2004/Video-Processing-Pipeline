package com.mininetflix.repository;

import com.mininetflix.model.Video;
import com.mininetflix.model.Video.VideoStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VideoRepository extends JpaRepository<Video, String> {

    List<Video> findByUserIdAndStatusNotOrderByCreatedAtDesc(String userId, VideoStatus status);

    Optional<Video> findByIdAndUserId(String id, String userId);

    boolean existsByIdAndMediaConvertJobId(String id, String jobId);

    @Query("SELECT COUNT(v) FROM Video v WHERE v.user.id = :userId AND v.status != 'DELETED' AND CAST(v.createdAt AS date) = CURRENT_DATE")
    long countTodayUploads(@Param("userId") String userId);

    List<Video> findByStatusIn(List<VideoStatus> statuses);

    Optional<Video> findByMediaConvertJobId(String jobId);
}
