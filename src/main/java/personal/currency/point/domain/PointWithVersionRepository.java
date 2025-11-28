package personal.currency.point.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PointWithVersionRepository extends JpaRepository<PointWithVersion, Long> {
}
