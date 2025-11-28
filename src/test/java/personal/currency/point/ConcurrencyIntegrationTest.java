package personal.currency.point;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import personal.currency.point.domain.Point;
import personal.currency.point.domain.PointRepository;
import personal.currency.point.domain.PointWithVersion;
import personal.currency.point.domain.PointWithVersionRepository;
import personal.currency.point.service.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동시성 제어 통합 테스트
 * - ExecutorService와 CountDownLatch를 사용한 동시성 테스트
 * - 5가지 전략 모두 동시 충전 상황에서 정확하게 작동하는지 검증
 */
@SpringBootTest
@ActiveProfiles("test")
class ConcurrencyIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyIntegrationTest.class);
    private static final int THREAD_COUNT = 10;
    private static final long CHARGE_AMOUNT = 100L;
    private static final long INITIAL_BALANCE = 1000L;

    @Autowired
    private PointRepository pointRepository;

    @Autowired
    private PointWithVersionRepository pointWithVersionRepository;

    @Autowired
    private PointService basePointService;

    @Autowired
    private SynchronizedChargeService synchronizedChargeService;

    @Autowired
    private ReentrantLockChargeService reentrantLockChargeService;

    @Autowired
    private PessimisticLockChargeService pessimisticLockChargeService;

    @Autowired
    private OptimisticLockChargeService optimisticLockChargeService;

    @Autowired
    private DistributedLockChargeService distributedLockChargeService;

    @BeforeEach
    void setUp() {
        pointRepository.deleteAll();
        pointWithVersionRepository.deleteAll();
    }

    @Test
    @DisplayName("BASE - 동시성 이슈 발생 (실패 케이스)")
    void testBaseConcurrencyIssue() throws InterruptedException {
        // Given
        Point point = pointRepository.save(new Point(INITIAL_BALANCE));
        Long userId = point.getId();

        // When
        executeConcurrentCharges(userId, CHARGE_AMOUNT, () ->
                basePointService.charge(userId, CHARGE_AMOUNT)
        );

        // Then
        Point result = pointRepository.findById(userId).orElseThrow();
        long expectedBalance = INITIAL_BALANCE + (THREAD_COUNT * CHARGE_AMOUNT);

        log.warn("BASE Test - Expected: {}, Actual: {}", expectedBalance, result.getBalance());
        // BASE는 동시성 제어가 없으므로 실패할 수 있음 (assertion 하지 않음)
    }

    @Test
    @DisplayName("1. Java Native Synchronized - 동시성 제어 성공")
    void testSynchronizedStrategy() throws InterruptedException {
        // Given
        Point point = pointRepository.save(new Point(INITIAL_BALANCE));
        Long userId = point.getId();

        // When
        executeConcurrentCharges(userId, CHARGE_AMOUNT, () ->
                synchronizedChargeService.charge(userId, CHARGE_AMOUNT)
        );

        // Then
        Point result = pointRepository.findById(userId).orElseThrow();
        long expectedBalance = INITIAL_BALANCE + (THREAD_COUNT * CHARGE_AMOUNT);
        assertThat(result.getBalance())
                .as("Synchronized 전략으로 정확한 잔액이 유지되어야 함")
                .isEqualTo(expectedBalance);
        log.info("✓ Synchronized Test Passed - Balance: {}", result.getBalance());
    }

    @Test
    @DisplayName("2. Java Explicit ReentrantLock - 동시성 제어 성공")
    void testReentrantLockStrategy() throws InterruptedException {
        // Given
        Point point = pointRepository.save(new Point(INITIAL_BALANCE));
        Long userId = point.getId();

        // When
        executeConcurrentCharges(userId, CHARGE_AMOUNT, () ->
                reentrantLockChargeService.charge(userId, CHARGE_AMOUNT)
        );

        // Then
        Point result = pointRepository.findById(userId).orElseThrow();
        long expectedBalance = INITIAL_BALANCE + (THREAD_COUNT * CHARGE_AMOUNT);
        assertThat(result.getBalance())
                .as("ReentrantLock 전략으로 정확한 잔액이 유지되어야 함")
                .isEqualTo(expectedBalance);
        log.info("✓ ReentrantLock Test Passed - Balance: {}", result.getBalance());
    }

    @Test
    @DisplayName("3. DB Pessimistic Lock - 동시성 제어 성공")
    void testPessimisticLockStrategy() throws InterruptedException {
        // Given
        Point point = pointRepository.save(new Point(INITIAL_BALANCE));
        Long userId = point.getId();

        // When
        executeConcurrentCharges(userId, CHARGE_AMOUNT, () ->
                pessimisticLockChargeService.charge(userId, CHARGE_AMOUNT)
        );

        // Then
        Point result = pointRepository.findById(userId).orElseThrow();
        long expectedBalance = INITIAL_BALANCE + (THREAD_COUNT * CHARGE_AMOUNT);
        assertThat(result.getBalance())
                .as("Pessimistic Lock 전략으로 정확한 잔액이 유지되어야 함")
                .isEqualTo(expectedBalance);
        log.info("✓ Pessimistic Lock Test Passed - Balance: {}", result.getBalance());
    }

    @Test
    @DisplayName("4. DB Optimistic Lock with Retry - 동시성 제어 성공")
    void testOptimisticLockStrategy() throws InterruptedException {
        // Given
        PointWithVersion pointWithVersion = pointWithVersionRepository.save(new PointWithVersion(INITIAL_BALANCE));
        Long versionedPointId = pointWithVersion.getId();

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger retryCount = new AtomicInteger(0);

        log.info("Starting Optimistic Lock test with PointWithVersion ID: {}", versionedPointId);

        // When
        ExecutorService executorService = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);

        for (int i = 0; i < THREAD_COUNT; i++) {
            executorService.submit(() -> {
                try {
                    optimisticLockChargeService.charge(versionedPointId, CHARGE_AMOUNT);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    log.error("Charge failed: {}", e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        executorService.shutdown();

        // Then
        PointWithVersion result = pointWithVersionRepository.findById(versionedPointId).orElseThrow();
        long expectedBalance = INITIAL_BALANCE + (THREAD_COUNT * CHARGE_AMOUNT);
        assertThat(result.getBalance())
                .as("Optimistic Lock 전략으로 정확한 잔액이 유지되어야 함")
                .isEqualTo(expectedBalance);
        assertThat(successCount.get())
                .as("모든 충전 시도가 성공해야 함")
                .isEqualTo(THREAD_COUNT);
        log.info("✓ Optimistic Lock Test Passed - Balance: {}, Success: {}/{}, Version: {}",
                result.getBalance(), successCount.get(), THREAD_COUNT, result.getVersion());
    }

    @Test
    @DisplayName("5. Distributed Lock (FakeRedis) - 동시성 제어 성공")
    void testDistributedLockStrategy() throws InterruptedException {
        // Given
        Point point = pointRepository.save(new Point(INITIAL_BALANCE));
        Long userId = point.getId();

        // When
        executeConcurrentCharges(userId, CHARGE_AMOUNT, () ->
                distributedLockChargeService.charge(userId, CHARGE_AMOUNT)
        );

        // Then
        Point result = pointRepository.findById(userId).orElseThrow();
        long expectedBalance = INITIAL_BALANCE + (THREAD_COUNT * CHARGE_AMOUNT);
        assertThat(result.getBalance())
                .as("Distributed Lock 전략으로 정확한 잔액이 유지되어야 함")
                .isEqualTo(expectedBalance);
        log.info("✓ Distributed Lock Test Passed - Balance: {}", result.getBalance());
    }

    @Test
    @DisplayName("대량 동시 충전 테스트 - 100명 동시 충전 (Pessimistic Lock)")
    void testHighConcurrency() throws InterruptedException {
        // Given
        long initialBalance = 0L;
        int threadCount = 100;
        long chargeAmount = 10L;
        Point point = pointRepository.save(new Point(initialBalance));
        Long userId = point.getId();

        // When
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    pessimisticLockChargeService.charge(userId, chargeAmount);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(60, TimeUnit.SECONDS);
        executorService.shutdown();

        long endTime = System.currentTimeMillis();

        // Then
        Point result = pointRepository.findById(userId).orElseThrow();
        long expectedBalance = initialBalance + (threadCount * chargeAmount);
        assertThat(result.getBalance())
                .as("100명의 동시 충전이 정확하게 처리되어야 함")
                .isEqualTo(expectedBalance);
        log.info("✓ High Concurrency Test Passed - {} threads in {}ms, Balance: {}",
                threadCount, (endTime - startTime), result.getBalance());
    }

    /**
     * 동시성 테스트를 위한 헬퍼 메서드
     */
    private void executeConcurrentCharges(Long userId, long amount, Runnable chargeTask) throws InterruptedException {
        ExecutorService executorService = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);

        for (int i = 0; i < THREAD_COUNT; i++) {
            executorService.submit(() -> {
                try {
                    chargeTask.run();
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(30, TimeUnit.SECONDS);
        executorService.shutdown();

        if (!completed) {
            throw new RuntimeException("Timeout waiting for concurrent operations");
        }
    }
}
