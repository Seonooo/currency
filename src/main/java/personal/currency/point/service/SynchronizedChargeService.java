package personal.currency.point.service;

import org.springframework.stereotype.Service;
import personal.currency.point.domain.Point;

/**
 * Java Native Synchronized 전략
 * - synchronized 키워드를 사용한 동시성 제어
 * - 메서드 레벨에서 모니터 락을 사용하여 한 번에 하나의 스레드만 접근 가능
 * - 장점: 간단하고 직관적
 * - 단점: 모든 사용자 요청이 순차 처리되어 성능 저하 가능
 */
@Service
public class SynchronizedChargeService {

    private final PointService pointService;

    public SynchronizedChargeService(PointService pointService) {
        this.pointService = pointService;
    }

    /**
     * synchronized 키워드를 통해 동시에 하나의 스레드만 이 메서드를 실행할 수 있음
     * @param userId 사용자 ID
     * @param amount 충전 금액
     * @return 충전된 포인트 정보
     */
    public synchronized Point charge(Long userId, long amount) {
        return pointService.charge(userId, amount);
    }
}
