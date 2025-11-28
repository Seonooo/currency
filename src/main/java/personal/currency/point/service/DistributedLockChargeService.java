package personal.currency.point.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import personal.currency.point.domain.Point;
import personal.currency.redis.FakeRedisLock;

/**
 * Distributed Lock (분산 락) 전략 - Redis Simulation
 * - FakeRedisLock을 사용하여 분산 환경에서의 락 제어 시뮬레이션
 * - Redis의 SETNX(SET if Not eXists) 명령어를 ConcurrentHashMap으로 시뮬레이션
 * - 장점: 여러 서버 인스턴스 간 동시성 제어 가능
 * - 단점: Redis 장애 시 서비스 영향, 네트워크 오버헤드, 락 타임아웃 관리 필요
 */
@Service
public class DistributedLockChargeService {

    private static final Logger log = LoggerFactory.getLogger(DistributedLockChargeService.class);
    private static final int MAX_RETRY_COUNT = 50; // 충분한 재시도 횟수 (10 threads * 200ms each = 2000ms + buffer)
    private static final long RETRY_DELAY_MS = 50; // 짧은 지연으로 빠른 재시도
    private static final String LOCK_KEY_PREFIX = "point:lock:";

    private final PointService pointService;
    private final FakeRedisLock fakeRedisLock;

    public DistributedLockChargeService(PointService pointService, FakeRedisLock fakeRedisLock) {
        this.pointService = pointService;
        this.fakeRedisLock = fakeRedisLock;
    }

    /**
     * 분산 락을 사용하여 동시성 제어
     * - tryLock()으로 락 획득 시도, 실패 시 재시도
     * - 최대 MAX_RETRY_COUNT만큼 락 획득 시도
     * - finally에서 반드시 unlock() 호출하여 락 해제
     *
     * @param userId 사용자 ID
     * @param amount 충전 금액
     * @return 충전된 포인트 정보
     */
    public Point charge(Long userId, long amount) {
        String lockKey = LOCK_KEY_PREFIX + userId;
        int retryCount = 0;
        boolean lockAcquired = false;

        try {
            // 락 획득 시도 (스핀락 방식)
            while (retryCount < MAX_RETRY_COUNT) {
                lockAcquired = fakeRedisLock.tryLock(lockKey);

                if (lockAcquired) {
                    log.debug("Lock acquired for userId: {} after {} attempts", userId, retryCount + 1);
                    break;
                }

                retryCount++;
                log.debug("Failed to acquire lock for userId: {}. Retry attempt: {}/{}",
                        userId, retryCount, MAX_RETRY_COUNT);

                if (retryCount >= MAX_RETRY_COUNT) {
                    log.error("Failed to acquire lock for userId: {} after {} attempts", userId, MAX_RETRY_COUNT);
                    throw new RuntimeException("Failed to acquire distributed lock after " + MAX_RETRY_COUNT + " attempts");
                }

                // 락 획득 실패 시 대기 후 재시도
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Thread interrupted while waiting for lock", e);
                }
            }

            // 락을 획득한 상태에서 충전 로직 수행
            return pointService.charge(userId, amount);

        } finally {
            // 락을 획득했다면 반드시 해제
            if (lockAcquired) {
                fakeRedisLock.unlock(lockKey);
                log.debug("Lock released for userId: {}", userId);
            }
        }
    }
}
