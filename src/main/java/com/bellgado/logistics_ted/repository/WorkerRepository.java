package com.bellgado.logistics_ted.repository;

import com.bellgado.logistics_ted.domain.Worker;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkerRepository extends JpaRepository<Worker, Integer> {}
