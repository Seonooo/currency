# 동시성 제어 기술 문서 (Concurrency Control Tech Spec)

> 티켓팅 시스템 좌석 예매 로직 기준

---

## 목차

1. [원인 분석 (Root Cause Analysis)](#1-원인-분석-root-cause-analysis)
2. [전략 수립 (Strategy)](#2-전략-수립-strategy)
3. [핵심 개념 정리](#3-핵심-개념-정리)
4. [결론](#4-결론)

---

## 1. 원인 분석 (Root Cause Analysis)

### 1.1 Read-Modify-Write (RMW) 패턴의 비원자성

고수준 언어의 `count++` 또는 `count--` 한 줄은 CPU가 실행하는 기계어 레벨에서는 **원자적이지 않다**. 최소 3단계의 독립적인 명령어로 나뉘어지며, 이를 **RMW(Read-Modify-Write) 패턴**이라고 한다.

| 단계 | 명령어 | 동작 |
|:---:|:---|:---|
| **1. Read** | `LOAD reg, [count]` | 메모리(RAM)의 값을 읽어 CPU 레지스터로 가져온다 |
| **2. Modify** | `ADD/DEC reg` | ALU가 레지스터의 값을 증가/감소시킨다 |
| **3. Write** | `STORE [count], reg` | 연산 결과 값을 다시 메모리에 덮어쓴다 |

---

### 1.2 갱신 손실(Lost Update) 발생 메커니즘

갱신 손실은 **단일 코어**와 **멀티 코어** 환경 모두에서 발생할 수 있으나, 원인은 미묘하게 다르다.

#### Case A: 단일 코어 환경 - Context Switching에 의한 시분할 문제

```
초기값: count = 100

1. Thread A Read 수행: 값 100을 읽어옴
2. ⚡ Context Switching 발생: OS가 Thread A를 멈추고 Thread B에게 CPU 할당
3. Thread B Read 수행: 값 100을 읽어옴 (메모리는 여전히 100)
4. Thread B Modify & Write 수행: 100 + 1 = 101을 메모리에 저장
5. ⚡ Context Switch 복귀: Thread A 실행 재개 (이전에 읽어둔 100 보유)
6. Thread A Modify & Write 수행: 100 + 1 = 101을 메모리에 덮어씀

❌ 결과: Thread B의 작업이 Thread A에 의해 덮어씌워져 사라짐 (Lost Update)
```

#### Case B: 멀티 코어 환경 - 병렬 실행에 의한 경쟁 상태

```
초기값: count = 100

1. Core 1 (Thread A): 메인 메모리에서 100을 읽어 자신의 L1 캐시에 로드
2. Core 2 (Thread B): 거의 동시에 메인 메모리에서 100을 읽어 자신의 L1 캐시에 로드
   (두 Read가 거의 동시에 발생하면, 캐시 일관성 프로토콜이 개입하기 전에 둘 다 100을 본다)
3. 각 코어가 독립적으로 Modify 수행 (100 → 101)
4. 각 코어가 Write 수행 - 둘 다 101을 기록

❌ 결과: 2번 증가했지만 101만 기록됨 (Lost Update)
```

> 💡 **핵심 포인트**: MESI 같은 캐시 일관성 프로토콜은 "쓰기 시점"의 일관성은 보장하지만, "읽기-수정-쓰기" 전체를 원자적으로 묶어주지는 않는다.

---

### 1.3 DB 격리 수준에서의 동시성 이슈

MySQL InnoDB의 기본 격리 수준인 **REPEATABLE READ**와 PostgreSQL의 **READ COMMITTED**에서 각각 다른 방식의 동시성 문제가 발생할 수 있다.

#### 시나리오 1: Phantom Read (PostgreSQL RC 환경)

```
1. User A가 좌석 목록 확인 (좌석 1개)
2. User B가 좌석 예매 후 결제 (좌석 0개)
3. User A가 같은 조건으로 좌석 다시 확인 (좌석 0개) - 데이터 불일치
```

#### 시나리오 2: MySQL의 Consistent Read vs Current Read 괴리

MySQL InnoDB에서 `SELECT`는 스냅샷 기준(Consistent Read)으로, `UPDATE/DELETE`는 최신 데이터 기준(Current Read)으로 동작하여 예상치 못한 결과가 발생할 수 있다.

---

## 2. 전략 수립 (Strategy)

### 2.1 Lock 획득 대기 전략: Pub/Sub 선택

| 구분 | Spin Lock | Pub/Sub |
|:---:|:---:|:---:|
| **Network I/O** | ❌ 1ms 간격 시 9,900회/0.1초 | ✅ 알림 시에만 발생 |
| **CPU 점유** | ❌ 무한 루프로 지속 점유 | ✅ 대기 중 OS에 반납 |
| **Redis 부하** | ❌ Self-DDoS 위험 | ✅ 최소화 |
| **구현 복잡도** | ✅ 단순 | ⚠️ Redisson으로 추상화 |

> ✅ **선택: Pub/Sub 방식**
> 
> 티켓팅 서비스의 확장성과 DB I/O를 고려할 때 Pub/Sub이 적합. Redisson 라이브러리가 Double Check, Timeout 등을 추상화하여 구현 복잡도 해결

---

### 2.2 Zombie Lock 방어: TTL 전략

#### 문제 상황

서버가 Lock을 획득한 상태에서 Crash되거나 배포로 인해 재시작되면 `unlock()`을 호출하지 못하고, Lock이 영원히 유지되어 **Deadlock** 상태에 빠진다.

#### TTL 산출 근거

| 항목 | 값 |
|:---|:---|
| 비즈니스 로직 수행 시간 (p99) | 500ms |
| 네트워크 지연, GC 등 예외 상황 고려 | 3~4배 |
| **최종 TTL 설정값** | **2초** |

#### Watchdog 전략 (Redisson)

TTL을 명시적으로 설정하지 않고 `lockWatchdogTimeout`을 5초 내로 튜닝하여 빠른 복구와 안정성 확보.

Redisson은 내부적으로 **Lua Script**를 사용하여 락 해제 시 소유자를 확인하므로, 서버 A가 뒤늦게 깨어나서 서버 B의 락을 풀어버리는 상황을 방지한다.

#### 이중 방어 구성

> ⚠️ Redis 통신 장애 등 극단적 상황을 대비해 **DB 레벨에서 낙관적 락(Version Check)**을 추가 적용하여 방어층 구성

---

### 2.3 AOP Trap 방어: Facade Pattern

#### 문제 상황

Spring AOP의 `@Transactional`은 메서드 리턴 **후(After)**에 커밋된다. 커밋되기 전에 Lock이 해제되면 다른 스레드가 아직 커밋되지 않은 과거 데이터를 읽게 된다.

#### 문제 발생 시나리오

```
1. Thread A: 로직 완료 → 락 해제 → (커밋 대기 중...)
2. Thread B: 락 획득 → 데이터 조회 (A가 아직 커밋 안 했으므로 과거 데이터 읽음)
3. Result: 락을 걸었음에도 데이터 정합성 깨짐
```

#### 해결책: Facade Pattern

Lock을 관리하는 외부 클래스(Facade)에서 트랜잭션이 있는 메서드를 호출하는 구조로 분리한다.

```java
// LockFacade.java
public void reserveSeat(Long seatId) {
    RLock lock = redissonClient.getLock("seat:" + seatId);
    lock.lock();
    try {
        reservationService.reserve(seatId);  // @Transactional
    } finally {
        lock.unlock();  // 트랜잭션 커밋 완료 후 해제
    }
}
```

| 장점 | 단점 |
|:---|:---|
| 구조 명확, 역할 분리, 테스트 용이 | 클래스 증가 |

---

### 2.4 Retry Storm 방어: Exponential Backoff + Jitter

#### 문제 상황 (Thundering Herd)

낙관적 락 실패 시 100명이 동시에 재시도하면 DB 커넥션 풀이 순식간에 고갈된다.

#### 해결 전략

| 전략 | 설명 |
|:---|:---|
| **Exponential Backoff** | 재시도 간격을 지수적으로 증가 (100ms → 200ms → 400ms → 800ms) |
| **Jitter** | 각 대기 시간에 무작위 값을 추가하여 재시도 시점 분산 |
| **Max Retry** | 최대 재시도 횟수 제한 (예: 3~5회) |
| **Max Delay Cap** | 대기 시간 상한선 설정 (예: 2초) |

```
delay = min(baseDelay * 2^attempt + random(0, jitter), maxDelay)
```

---

## 3. 핵심 개념 정리

### 3.1 임계 구역 (Critical Section)

공유 자원(`count`)에 접근하는 RMW 명령어들의 집합 구간. 이 구간은 쪼개지지 않고 **한 덩어리처럼 실행**되어야 한다.

---

### 3.2 동시성 제어 (Lock)

> 💡 **Lock은 Context Switching을 막는 것이 아닌, 임계 구역에 한 번에 하나의 스레드만 진입하도록 상호 배제(Mutual Exclusion)를 보장하는 기술**

#### Lock 동작 원리

```
1. Thread A가 Lock을 획득하고 작업 중 Context Switching 발생
2. Thread B가 CPU 할당을 받았지만 Lock 획득 불가로 대기(Block) 상태
3. Thread A가 작업 완료 후 Lock 해제 시 Thread B 진입 가능
```

---

### 3.3 하드웨어 수준의 해결책

| 기술 | 설명 |
|:---|:---|
| **CAS (Compare-And-Swap)** | '내가 읽은 값이 100일 때만 101로 바꿔줘'를 CPU가 단 하나의 원자적 명령어로 처리 (Lock-free 핵심) |
| **Memory Barrier** | 컴파일러나 CPU가 성능 최적화를 위해 명령어 순서를 바꾸는 것을 방지 |
| **volatile (Java)** | 메모리 배리어 삽입으로 가시성 보장. 단, RMW 원자성은 미보장 (`count++` 여전히 불안전) |

---

## 4. 결론

동시성 문제의 **근본 원인**은 RMW 3단계가 **비원자적 연산**이기 때문이다.

이를 해결하기 위해 **다층 방어 전략**이 필요하다:

| 레벨 | 전략 |
|:---|:---|
| **애플리케이션** | Lock, Pub/Sub, Facade Pattern, Exponential Backoff + Jitter |
| **DB** | 격리 수준, 낙관적 락 / 비관적 락 |
| **하드웨어** | CAS, Memory Barrier |

특히 티켓팅과 같은 **고부하 시스템**에서는 단순히 Lock을 거는 것을 넘어:

- ✅ Lock 대기 전략 (Pub/Sub)
- ✅ Zombie Lock 방어 (TTL/Watchdog)
- ✅ AOP Trap 방어 (Facade Pattern)
- ✅ Retry Storm 방어 (Exponential Backoff + Jitter)

까지 고려해야 **안정적인 서비스 운영**이 가능하다.

---

## 참고 자료

- [Redisson Documentation](https://redisson.org/documentation.html)
- [MySQL InnoDB Locking](https://dev.mysql.com/doc/refman/8.0/en/innodb-locking.html)
- [PostgreSQL MVCC](https://www.postgresql.org/docs/current/mvcc.html)
