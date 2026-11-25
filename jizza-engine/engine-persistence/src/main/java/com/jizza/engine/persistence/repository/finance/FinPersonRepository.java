package com.jizza.engine.persistence.repository.finance;

import com.jizza.engine.persistence.entity.finance.FinPersonEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FinPersonRepository extends JpaRepository<FinPersonEntity, UUID> {

  List<FinPersonEntity> findByUserIdOrderBySortOrderAscCreatedAtAsc(UUID userId);

  Optional<FinPersonEntity> findByIdAndUserId(UUID id, UUID userId);

  Optional<FinPersonEntity> findByUserIdAndKind(UUID userId, String kind);
}
