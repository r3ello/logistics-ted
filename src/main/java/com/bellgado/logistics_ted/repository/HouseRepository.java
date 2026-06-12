package com.bellgado.logistics_ted.repository;

import com.bellgado.logistics_ted.domain.House;
import com.bellgado.logistics_ted.domain.ScaffoldStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HouseRepository extends JpaRepository<House, Integer> {

    List<House> findAllByOrderByIdAsc();

    List<House> findByScaffoldStatusAndLatIsNotNullAndLngIsNotNull(ScaffoldStatus status);

    java.util.Optional<House> findByCheckinToken(String checkinToken);
}
