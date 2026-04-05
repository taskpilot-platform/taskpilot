package com.taskpilot.users.repository;

import com.taskpilot.users.entity.SystemSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SystemSettingRepository extends JpaRepository<SystemSettingEntity, String> {

   @Query("SELECT s FROM SystemSettingEntity s WHERE " +
         "(:keyword IS NULL OR LOWER(s.keyName) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
         "OR LOWER(s.description) LIKE LOWER(CONCAT('%', :keyword, '%')))")
   List<SystemSettingEntity> findByKeyword(@Param("keyword") String keyword);
}
