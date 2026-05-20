package com.bellgado.logistics_ted.repository;

import com.bellgado.logistics_ted.domain.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierRepository extends JpaRepository<Supplier, Integer> {
}
