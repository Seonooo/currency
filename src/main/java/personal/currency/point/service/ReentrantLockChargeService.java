package personal.currency.point.service;

import org.springframework.stereotype.Service;
import personal.currency.point.domain.Point;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Java Explicit Lock (ReentrantLock) 전략
 * - ReentrantLock을 사용한 명시적 락 제어
 * - synchronized보다 더 유연한 락 제어 가능 (tryLock, 타임아웃 등)
 * - 장점: 공정성 설정, 타임아웃, 인터럽트 가능
 * - 단점: finally에서 unlock을 명시적으로 해줘야 함 (실수 가능성)
 */
@Service
public class ReentrantLockChargeService {

    private final PointService pointService;
    private final ReentrantLock lock = new ReentrantLock(true); // fair lock (공정성 보장)

    public ReentrantLockChargeService(PointService pointService) {
        this.pointService = pointService;
    }

    /**
     * ReentrantLock을 사용하여 동시성 제어
     * - lock()으로 락 획득, finally에서 unlock()으로 락 해제
     * - fair lock을 사용하여 대기 순서대로 처리
     *
     * @param userId 사용자 ID
     * @param amount 충전 금액
     * @return 충전된 포인트 정보
     */
    public Point charge(Long userId, long amount) {
        lock.lock();
        try {
            return pointService.charge(userId, amount);
        } finally {
            lock.unlock();
        }
    }
}
