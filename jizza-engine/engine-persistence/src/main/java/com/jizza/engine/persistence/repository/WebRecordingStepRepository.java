package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.WebRecordingStepEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface WebRecordingStepRepository
    extends JpaRepository<WebRecordingStepEntity, WebRecordingStepEntity.Key> {

  @Query("select s from WebRecordingStepEntity s where s.recordingId = :id order by s.position, s.seq")
  List<WebRecordingStepEntity> ordered(UUID id);

  @Modifying
  @Query("delete from WebRecordingStepEntity s where s.recordingId = :id")
  void deleteAllFor(UUID id);
}
