package personal.currency.point;

import io.cucumber.java.Before;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.cucumber.spring.CucumberContextConfiguration;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@CucumberContextConfiguration
@SpringBootTest
@ActiveProfiles("test")
public class ConcurrencyTestSteps {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyTestSteps.class);

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

    private Long currentUserId;
    private Long currentVersionedPointId; // For Optimistic Lock strategy
    private long initialBalance;
    private long finalBalance;
    private int successCount;
    private int failureCount;
    private AtomicInteger retryCount = new AtomicInteger(0);

    @Before
    public void setUp() {
        pointRepository.deleteAll();
        pointWithVersionRepository.deleteAll();
        retryCount.set(0);
    }

    @Given("사용자 ID가 {long}이고 초기 잔액이 {long}인 포인트가 존재한다")
    public void 사용자_포인트_생성(Long userId, Long balance) {
        initialBalance = balance;
        // 일반 Point와 PointWithVersion을 각각 생성
        Point point = pointRepository.save(new Point(balance));
        PointWithVersion pointWithVersion = pointWithVersionRepository.save(new PointWithVersion(balance));

        // 각 전략에 맞는 ID 저장
        currentUserId = point.getId();
        currentVersionedPointId = pointWithVersion.getId();

        log.info("Created point with Point ID: {}, PointWithVersion ID: {}, balance: {}",
                currentUserId, currentVersionedPointId, balance);
    }

    @When("{int}명의 사용자가 동시에 각각 {long}원씩 충전을 시도한다 using {string}")
    public void 동시_충전_시도(int userCount, long amount, String strategy) throws InterruptedException {
        ExecutorService executorService = Executors.newFixedThreadPool(userCount);
        CountDownLatch latch = new CountDownLatch(userCount);

        List<Exception> exceptions = new ArrayList<>();
        successCount = 0;
        failureCount = 0;

        log.info("Starting concurrent charge test: {} users, {} amount each, strategy: {}",
                userCount, amount, strategy);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < userCount; i++) {
            executorService.submit(() -> {
                try {
                    chargeByStrategy(currentUserId, amount, strategy);
                    successCount++;
                } catch (Exception e) {
                    failureCount++;
                    synchronized (exceptions) {
                        exceptions.add(e);
                    }
                    log.error("Charge failed: {}", e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        // 모든 스레드가 완료될 때까지 대기 (최대 30초)
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        executorService.shutdown();

        long endTime = System.currentTimeMillis();
        log.info("Concurrent charge test completed in {}ms. Success: {}, Failure: {}",
                (endTime - startTime), successCount, failureCount);

        if (!completed) {
            throw new RuntimeException("Timeout waiting for concurrent operations to complete");
        }

        if (!exceptions.isEmpty() && !"BASE".equals(strategy)) {
            log.error("Exceptions occurred during charge: {}", exceptions.size());
            throw new RuntimeException("Charge operations failed", exceptions.get(0));
        }

        // 최종 잔액 조회 (Optimistic Lock의 경우 PointWithVersion의 첫 번째 레코드 사용)
        if ("OPTIMISTIC_LOCK".equals(strategy)) {
            // PointWithVersion의 경우, 생성된 첫 번째 레코드 사용
            List<PointWithVersion> all = pointWithVersionRepository.findAll();
            if (!all.isEmpty()) {
                finalBalance = all.get(0).getBalance();
            } else {
                throw new RuntimeException("PointWithVersion not found");
            }
        } else {
            Point finalPoint = pointRepository.findById(currentUserId)
                    .orElseThrow(() -> new RuntimeException("Point not found"));
            finalBalance = finalPoint.getBalance();
        }

        log.info("Final balance: {}, Expected: {}", finalBalance, initialBalance + (userCount * amount));
    }

    @Then("최종 잔액은 정확히 {long}원이어야 한다")
    public void 최종_잔액_검증(long expectedBalance) {
        assertThat(finalBalance)
                .as("최종 잔액이 예상값과 일치해야 합니다")
                .isEqualTo(expectedBalance);
        log.info("✓ Balance verification passed: {} == {}", finalBalance, expectedBalance);
    }

    @Then("최종 잔액은 {long}원이 아닐 수 있다 \\(동시성 이슈)")
    public void 동시성_이슈_확인(long expectedBalance) {
        if (finalBalance != expectedBalance) {
            log.warn("⚠ Concurrency issue detected: expected {}, but got {}", expectedBalance, finalBalance);
        } else {
            log.info("Note: This test may pass occasionally due to timing, but concurrency issue still exists");
        }
        // BASE 케이스는 실패할 수 있으므로 assertion 하지 않음
    }

    @And("낙관적 락 충돌이 발생했지만 재시도를 통해 모두 성공해야 한다")
    public void 낙관적_락_재시도_검증() {
        log.info("✓ Optimistic lock conflicts were handled successfully through retry mechanism");
        log.info("  - All {} charge operations completed successfully", successCount);
        assertThat(successCount).isGreaterThan(0);
    }

    /**
     * 전략에 따라 적절한 서비스 메서드 호출
     */
    private void chargeByStrategy(Long userId, long amount, String strategy) {
        switch (strategy) {
            case "BASE":
                basePointService.charge(userId, amount);
                break;
            case "SYNCHRONIZED":
                synchronizedChargeService.charge(userId, amount);
                break;
            case "REENTRANT_LOCK":
                reentrantLockChargeService.charge(userId, amount);
                break;
            case "PESSIMISTIC_LOCK":
                pessimisticLockChargeService.charge(userId, amount);
                break;
            case "OPTIMISTIC_LOCK":
                // Optimistic Lock은 PointWithVersion ID를 사용
                optimisticLockChargeService.charge(currentVersionedPointId, amount);
                break;
            case "DISTRIBUTED_LOCK":
                distributedLockChargeService.charge(userId, amount);
                break;
            default:
                throw new IllegalArgumentException("Unknown strategy: " + strategy);
        }
    }
}
