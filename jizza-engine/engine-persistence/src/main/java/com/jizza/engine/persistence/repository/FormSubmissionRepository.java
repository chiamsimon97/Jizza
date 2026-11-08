package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.FormSubmissionEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FormSubmissionRepository extends JpaRepository<FormSubmissionEntity, UUID> {
  List<FormSubmissionEntity> findByFormIdOrderBySubmittedAtDesc(UUID formId, Pageable page);
  long countByFormId(UUID formId);
}
