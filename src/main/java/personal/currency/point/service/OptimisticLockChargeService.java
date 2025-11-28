package personal.currency.point.service;

import jakarta.persistence.OptimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import personal.currency.point.domain.Point;
import personal.currency.point.domain.PointWithVersion;
import personal.currency.point.domain.PointWithVersionRepository;

/**
 * DB Optimistic Lock (낙관적 락) 전략
 * - @Version 어노테이션을 사용하여 버전 관리
 * - 충돌 발생 시 OptimisticLockException이 발생하고 재시도 로직으로 처리
 * - 장점: 락을 사용하지 않아 성능이 좋음, 충돌이 적은 경우 효율적
 * - 단점: 충돌이 빈번한 경우 재시도 오버헤드, 비즈니스 로직에서 재시도 처리 필요
 */
@Service
public class OptimisticLockChargeService {

    private static final Logger log = LoggerFactory.getLogger(OptimisticLockChargeService.class);
    private static final int MAX_RETRY_COUNT = 20; // 충분한 재시도 횟수
    private static final long RETRY_DELAY_MS = 30; // 짧은 지연으로 빠른 재시도

    private final PointWithVersionRepository pointRepository;

    public OptimisticLockChargeService(PointWithVersionRepository pointRepository) {
        this.pointRepository = pointRepository;
    }

    /**
     * Optimistic Lock을 사용하여 동시성 제어
     * - 충돌 발생 시 최대 MAX_RETRY_COUNT만큼 재시도
     * - 지수 백오프 전략으로 재시도 간격 증가
     *
     * @param userId 사용자 ID
     * @param amount 충전 금액
     * @return 충전된 포인트 정보
     */
    public Point charge(Long userId, long amount) {
        int retryCount = 0;

        while (retryCount < MAX_RETRY_COUNT) {
            try {
                return attemptCharge(userId, amount);
            } catch (OptimisticLockException | ObjectOptimisticLockingFailureException e) {
                retryCount++;
                log.warn("Optimistic lock conflict occurred for userId: {}. Retry attempt: {}/{}",
                        userId, retryCount, MAX_RETRY_COUNT);

                if (retryCount >= MAX_RETRY_COUNT) {
                    log.error("Max retry attempts reached for userId: {}", userId);
                    throw new RuntimeException("Failed to charge point after " + MAX_RETRY_COUNT + " attempts", e);
                }

                // 지수 백오프: 재시도 시 대기 시간을 지수적으로 증가
                long delayMs = RETRY_DELAY_MS * (long) Math.pow(2, retryCount - 1);
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Thread interrupted during retry delay", ie);
                }
            }
        }

        throw new RuntimeException("Unexpected error: exceeded retry loop");
    }

    /**
     * 실제 충전 로직 수행
     * - @Transactional로 트랜잭션 관리
     * - version 필드가 자동으로 체크되어 충돌 시 예외 발생
     */
    @Transactional
    protected Point attemptCharge(Long userId, long amount) {
        PointWithVersion point = pointRepository.findById(userId)
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

        // save 시점에 version이 변경되었다면 OptimisticLockException 발생
        PointWithVersion saved = pointRepository.save(point);

        // Point 타입으로 변환하여 반환
        return new Point(saved.getId(), saved.getBalance());
    }
}
