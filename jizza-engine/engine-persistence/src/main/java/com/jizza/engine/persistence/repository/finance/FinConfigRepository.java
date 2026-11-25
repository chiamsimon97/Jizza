package com.jizza.engine.persistence.repository.finance;

import com.jizza.engine.persistence.entity.finance.FinConfigEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FinConfigRepository extends JpaRepository<FinConfigEntity, UUID> {

  /** Every system row and every row of this user, oldest version first. */
  @Query("select c from FinConfigEntity c where c.userId is null or c.userId = :userId "
      + "order by c.key asc, c.effectiveFrom asc")
  List<FinConfigEntity> findVisibleTo(@Param("userId") UUID userId);

  List<FinConfigEntity> findByUserIdAndKey(UUID userId, String key);

  List<FinConfigEntity> findByUserIdOrderByKeyAsc(UUID userId);
}
