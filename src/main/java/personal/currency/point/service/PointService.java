package personal.currency.point.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import personal.currency.point.domain.Point;
import personal.currency.point.domain.PointRepository;

@Service
public class PointService {

    private final PointRepository pointRepository;

    public PointService(PointRepository pointRepository) {
        this.pointRepository = pointRepository;
    }

    @Transactional
    public Point charge(Long userId, long amount) {
        Point point = pointRepository.findById(userId).orElseThrow();

        long currentBalance = point.getBalance();

        // 로직 수행 시간 시뮬레이션 (동시성 이슈 유발)
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
        }

        point.setBalance(currentBalance + amount);
        return pointRepository.save(point);
    }
}