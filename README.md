# 동시성 제어 전략 구현 프로젝트

## 📝 프로젝트 개요

이 프로젝트는 포인트 충전 시스템에서 발생할 수 있는 동시성 문제를 해결하기 위해 **5가지 동시성 제어 전략**을 구현하고, 각 전략의 동작을 검증한 프로젝트입니다.

## 🎯 구현 내용

### 1. Base Code (동시성 이슈 발생)
- **파일**: `PointService.java`
- **설명**: 동시성 제어가 없는 기본 서비스
- **문제점**: 10명이 동시에 100원씩 충전 시, 예상 잔액 1000원이 아닌 불일치 발생

### 2. Java Native - Synchronized
- **파일**: `SynchronizedChargeService.java`
- **방식**: `synchronized` 키워드 사용
- **장점**: 간단하고 직관적
- **단점**: 모든 요청이 순차 처리되어 성능 저하 가능
- **적용 범위**: 단일 JVM 환경

```java
public synchronized Point charge(Long userId, long amount) {
    return pointService.charge(userId, amount);
}
```

### 3. Java Explicit - ReentrantLock
- **파일**: `ReentrantLockChargeService.java`
- **방식**: `ReentrantLock` 사용
- **장점**: Fair Lock 설정, 타임아웃, 인터럽트 가능
- **단점**: finally에서 unlock 필수 (실수 가능성)
- **적용 범위**: 단일 JVM 환경

```java
private final ReentrantLock lock = new ReentrantLock(true); // fair lock

public Point charge(Long userId, long amount) {
    lock.lock();
    try {
        return pointService.charge(userId, amount);
    } finally {
        lock.unlock();
    }
}
```

### 4. DB Pessimistic Lock (비관적 락)
- **파일**: `PessimisticLockChargeService.java`
- **방식**: `SELECT FOR UPDATE` 사용
- **장점**: 데이터 정합성 보장, 충돌이 빈번한 경우 효율적
- **단점**: 데드락 가능성, 대기 시간 증가
- **적용 범위**: 분산 환경 (DB 레벨)

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select p from Point p where p.id = :id")
Optional<Point> findByIdWithPessimisticLock(@Param("id") Long id);
```

### 5. DB Optimistic Lock (낙관적 락) + Retry
- **파일**: `OptimisticLockChargeService.java`, `PointWithVersion.java`
- **방식**: `@Version` 어노테이션 + 지수 백오프 재시도
- **장점**: 락을 사용하지 않아 성능이 좋음, 충돌이 적은 경우 효율적
- **단점**: 충돌이 빈번한 경우 재시도 오버헤드
- **재시도 전략**: 최대 20회, 지수 백오프 (30ms * 2^n)
- **적용 범위**: 분산 환경 (DB 레벨)

```java
@Version
@Column(nullable = false)
private long version;

// 충돌 발생 시 최대 MAX_RETRY_COUNT만큼 재시도
while (retryCount < MAX_RETRY_COUNT) {
    try {
        return attemptCharge(userId, amount);
    } catch (OptimisticLockException e) {
        // 지수 백오프 재시도
    }
}
```

### 6. Distributed Lock (분산 락) - Redis Simulation
- **파일**: `DistributedLockChargeService.java`, `FakeRedisLock.java`
- **방식**: `ConcurrentHashMap`을 사용한 Redis SETNX 시뮬레이션
- **장점**: 여러 서버 인스턴스 간 동시성 제어 가능
- **단점**: Redis 장애 시 서비스 영향, 네트워크 오버헤드
- **재시도 전략**: 최대 50회, 스핀락 방식
- **적용 범위**: 분산 환경 (Multi-Instance)

```java
public boolean tryLock(String key) {
    return lockStore.putIfAbsent(key, "LOCKED") == null;
}

public void unlock(String key) {
    lockStore.remove(key);
}
```

## 🧪 테스트 구성

### Cucumber BDD 테스트
- **파일**:
  - `src/test/resources/features/point_concurrency.feature` - BDD 시나리오
  - `src/test/java/personal/currency/point/ConcurrencyTestSteps.java` - Step Definitions
  - `src/test/java/personal/currency/point/CucumberTestRunner.java` - 테스트 러너

- **시나리오 수**: 8개
- **특징**:
  - **Given-When-Then** 구조로 비즈니스 시나리오 명확화
  - **한글 시나리오**로 가독성 향상
  - **ExecutorService & CountDownLatch** 활용한 동시성 검증
  - 각 전략별 10명 동시 충전 테스트
  - 대량 동시 충전 테스트 (100명)

**시나리오 목록**:
1. Base PointService - 동시성 이슈 발생 (실패 케이스)
2. Java Native Synchronized - 동시성 제어 성공
3. Java Explicit ReentrantLock - 동시성 제어 성공
4. DB Pessimistic Lock - 동시성 제어 성공
5. DB Optimistic Lock with Retry - 동시성 제어 성공
6. Distributed Lock (FakeRedis) - 동시성 제어 성공
7. 대량 동시 충전 테스트 - Synchronized (100명)
8. 대량 동시 충전 테스트 - Pessimistic Lock (100명)

### 테스트 실행 방법

```bash
# Cucumber BDD 테스트 실행
./gradlew test

# 또는 명시적으로
./gradlew test --tests "personal.currency.point.CucumberTestRunner"
```

### 테스트 결과 확인

```bash
# JUnit HTML 리포트
build/reports/tests/test/index.html

# Cucumber HTML 리포트 (상세 시나리오 결과)
build/cucumber-reports/cucumber.html

# Cucumber JSON 리포트
build/cucumber-reports/cucumber.json
```

## 📊 성능 비교

| 전략 | 10명 동시 충전 | 100명 동시 충전 | 적합한 환경 |
|------|---------------|----------------|------------|
| Synchronized | ✅ 성공 | ✅ 성공 | 단일 서버 |
| ReentrantLock | ✅ 성공 | ✅ 성공 | 단일 서버 |
| Pessimistic Lock | ✅ 성공 | ✅ 성공 | 분산 환경, 높은 충돌률 |
| Optimistic Lock | ✅ 성공 | ⚠️ 재시도 오버헤드 | 분산 환경, 낮은 충돌률 |
| Distributed Lock | ✅ 성공 | ✅ 성공 | 분산 환경 (Multi-Instance) |

## 🏗️ 프로젝트 구조

```
src/main/java/personal/currency/
├── point/
│   ├── domain/
│   │   ├── Point.java                          # 일반 Point 엔티티
│   │   ├── PointWithVersion.java               # Optimistic Lock용 엔티티 (@Version)
│   │   ├── PointRepository.java                # Point 리포지토리 (Pessimistic Lock 쿼리 포함)
│   │   └── PointWithVersionRepository.java     # PointWithVersion 리포지토리
│   └── service/
│       ├── PointService.java                   # Base 서비스 (동시성 제어 없음)
│       ├── SynchronizedChargeService.java      # 1. synchronized 전략
│       ├── ReentrantLockChargeService.java     # 2. ReentrantLock 전략
│       ├── PessimisticLockChargeService.java   # 3. Pessimistic Lock 전략
│       ├── OptimisticLockChargeService.java    # 4. Optimistic Lock + Retry 전략
│       └── DistributedLockChargeService.java   # 5. Distributed Lock 전략
└── redis/
    └── FakeRedisLock.java                      # Redis 분산 락 시뮬레이션

src/test/
├── java/personal/currency/point/
│   ├── ConcurrencyIntegrationTest.java         # JUnit 통합 테스트
│   ├── ConcurrencyTestSteps.java               # Cucumber Step Definitions
│   └── CucumberTestRunner.java                 # Cucumber 테스트 러너
└── resources/
    ├── application-test.yml                    # 테스트용 H2 DB 설정
    └── features/
        └── point_concurrency.feature           # Cucumber BDD 시나리오
```

## 🔧 기술 스택

- **Language**: Java 21
- **Framework**: Spring Boot 3.4.12
- **Database**: H2 (테스트), MySQL (운영)
- **Testing**: JUnit 5, Cucumber 7.20.1, AssertJ
- **Build Tool**: Gradle 8.14.3
- **ORM**: JPA/Hibernate
- **Container**: Docker, Docker Compose

## 💡 핵심 학습 포인트

### 1. 동시성 문제의 본질
- Race Condition 이해
- Lost Update 문제
- 트랜잭션 격리 수준

### 2. 락의 종류와 특성
- **Mutex vs Semaphore**
- **Fair vs Unfair Lock**
- **Pessimistic vs Optimistic**

### 3. 재시도 전략
- **지수 백오프 (Exponential Backoff)**
- **선형 백오프 (Linear Backoff)**
- **최대 재시도 횟수 설정**

### 4. 분산 환경 고려사항
- 단일 JVM 락의 한계
- DB 레벨 락의 필요성
- Redis 분산 락의 장단점

## ✅ 테스트 결과

```
BUILD SUCCESSFUL

9 tests completed, 9 passed (Cucumber Scenarios)

CucumberTestRunner:
✓ Base PointService - 동시성 이슈 발생 (실패 케이스)
✓ Java Native Synchronized - 동시성 제어 성공
✓ Java Explicit ReentrantLock - 동시성 제어 성공
✓ DB Pessimistic Lock - 동시성 제어 성공
✓ DB Optimistic Lock with Retry - 동시성 제어 성공
✓ Distributed Lock (FakeRedis) - 동시성 제어 성공
✓ 대량 동시 충전 테스트 - Synchronized
✓ 대량 동시 충전 테스트 - Pessimistic Lock
✓ Application Context Loads
```

**모든 동시성 제어 전략이 Cucumber BDD 시나리오를 통해 검증되었습니다.**

## 🚀 실행 방법

### 🧪 Cucumber BDD 테스트 실행 (Docker 불필요)

테스트는 H2 인메모리 DB를 사용하므로 **어떤 환경에서도 바로 실행 가능**합니다.

```bash
# Cucumber BDD 테스트 실행
./gradlew test

# 테스트 리포트 확인
# - JUnit 스타일: build/reports/tests/test/index.html
# - Cucumber 리포트: build/cucumber-reports/cucumber.html
# - Cucumber JSON: build/cucumber-reports/cucumber.json
```

### 🐳 Docker Compose로 전체 환경 실행

```bash
# 1. MySQL + 애플리케이션 모두 실행
docker-compose up -d

# 2. 로그 확인
docker-compose logs -f app

# 3. 접속
# http://localhost:8080

# 4. 종료
docker-compose down

# 5. 데이터 포함 완전 삭제
docker-compose down -v
```

### 💻 로컬 개발 환경 실행

```bash
# Option 1: Docker로 MySQL만 실행
docker-compose up mysql -d
./gradlew bootRun

# Option 2: 완전 로컬 환경 (MySQL 설치 필요)
# MySQL 설치 후
./gradlew bootRun
```

📖 **상세한 Docker 가이드**: [DOCKER_GUIDE.md](DOCKER_GUIDE.md) 참고

## 📌 주의사항

1. **H2 데이터베이스**: 테스트는 H2 인메모리 DB 사용, 별도 설치 불필요
2. **MySQL 연결**: 운영 환경에서는 `application.yml`에서 MySQL 설정 필요
3. **Optimistic Lock**: 대량 동시 충돌 시 재시도 오버헤드가 크므로 충돌이 적은 환경에 적합
4. **Distributed Lock**: 실제 Redis 연동 시 Redisson 또는 Lettuce 라이브러리 사용 권장

## 👨‍💻 작성자

시니어 개발자 수준의 코드 품질과 문서화를 지향하여 작성되었습니다.

---

**라이센스**: MIT License
