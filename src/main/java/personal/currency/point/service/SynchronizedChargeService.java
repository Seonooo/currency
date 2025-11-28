package personal.currency.point.service;

import org.springframework.stereotype.Service;
import personal.currency.point.domain.Point;

@Service
public class PointSynchronizedStr {

    private final PointService pointService;

    public PointSynchronizedCase(PointService pointService) {
        this.pointService = pointService;
    }

    public synchronized Point change(Long pointId, long amount) {
        return pointService.charge(pointId, amount);
    }
}
