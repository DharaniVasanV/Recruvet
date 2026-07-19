package com.fakejobpostsystem.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.fakejobpostsystem.model.Institution;

public interface InstitutionRepository extends JpaRepository<Institution, Long> {

    Optional<Institution> findByVerifiedEmailDomainIgnoreCase(String verifiedEmailDomain);

    List<Institution> findAllByOrderByNameAsc();
}
