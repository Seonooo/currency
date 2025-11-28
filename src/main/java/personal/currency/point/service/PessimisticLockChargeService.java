package personal.currency.point.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import personal.currency.point.domain.Point;
import personal.currency.point.domain.PointRepository;

/**
 * DB Pessimistic Lock (비관적 락) 전략
 * - SELECT FOR UPDATE를 사용하여 데이터베이스 레벨에서 락 설정
 * - 트랜잭션이 커밋될 때까지 다른 트랜잭션이 해당 row를 수정할 수 없음
 * - 장점: 데이터 정합성 보장, 충돌이 빈번한 경우 효율적
 * - 단점: 데드락 가능성, 대기 시간 증가, DB 커넥션 점유 시간 증가
 */
@Service
public class PessimisticLockChargeService {

    private final PointRepository pointRepository;

    public PessimisticLockChargeService(PointRepository pointRepository) {
        this.pointRepository = pointRepository;
    }

    /**
     * Pessimistic Lock을 사용하여 동시성 제어
     * - findByIdWithPessimisticLock()을 통해 SELECT FOR UPDATE 실행
     * - 트랜잭션 범위 내에서 다른 트랜잭션이 대기하게 됨
     *
     * @param userId 사용자 ID
     * @param amount 충전 금액
     * @return 충전된 포인트 정보
     */
    @Transactional
    public Point charge(Long userId, long amount) {
        // SELECT FOR UPDATE로 락 획득
        Point point = pointRepository.findByIdWithPessimisticLock(userId)
                .orElseThrow(() -> new IllegalArgumentException("Point not found for userId: " + userId));

        long currentBalance = point.getBalance();

        // 로직 수행 시간 시뮬레이션 (동시성 이슈 유발)
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Thread interrupted during charge operation", e);
        }

        point.setBalance(currentBalance + amount);
        return pointRepository.save(point);
    }
}
