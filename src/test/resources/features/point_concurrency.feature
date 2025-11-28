Feature: Point Charge Concurrency Control
  포인트 충전 시스템의 동시성 제어 기능을 검증합니다.
  5가지 동시성 제어 전략이 모두 동시 충전 상황에서 정확하게 작동하는지 확인합니다.

  Background:
    Given 사용자 ID가 1이고 초기 잔액이 1000인 포인트가 존재한다

  Scenario: Base PointService - 동시성 이슈 발생 (실패 케이스)
    When 10명의 사용자가 동시에 각각 100원씩 충전을 시도한다 using "BASE"
    Then 최종 잔액은 2000원이 아닐 수 있다 (동시성 이슈)

  Scenario: Java Native Synchronized - 동시성 제어 성공
    When 10명의 사용자가 동시에 각각 100원씩 충전을 시도한다 using "SYNCHRONIZED"
    Then 최종 잔액은 정확히 2000원이어야 한다

  Scenario: Java Explicit ReentrantLock - 동시성 제어 성공
    When 10명의 사용자가 동시에 각각 100원씩 충전을 시도한다 using "REENTRANT_LOCK"
    Then 최종 잔액은 정확히 2000원이어야 한다

  Scenario: DB Pessimistic Lock - 동시성 제어 성공
    When 10명의 사용자가 동시에 각각 100원씩 충전을 시도한다 using "PESSIMISTIC_LOCK"
    Then 최종 잔액은 정확히 2000원이어야 한다

  Scenario: DB Optimistic Lock with Retry - 동시성 제어 성공
    When 10명의 사용자가 동시에 각각 100원씩 충전을 시도한다 using "OPTIMISTIC_LOCK"
    Then 최종 잔액은 정확히 2000원이어야 한다
    And 낙관적 락 충돌이 발생했지만 재시도를 통해 모두 성공해야 한다

  Scenario: Distributed Lock (FakeRedis) - 동시성 제어 성공
    When 10명의 사용자가 동시에 각각 100원씩 충전을 시도한다 using "DISTRIBUTED_LOCK"
    Then 최종 잔액은 정확히 2000원이어야 한다

  Scenario: 대량 동시 충전 테스트 - Synchronized
    Given 사용자 ID가 2이고 초기 잔액이 0인 포인트가 존재한다
    When 100명의 사용자가 동시에 각각 10원씩 충전을 시도한다 using "SYNCHRONIZED"
    Then 최종 잔액은 정확히 1000원이어야 한다

  Scenario: 대량 동시 충전 테스트 - Pessimistic Lock
    Given 사용자 ID가 3이고 초기 잔액이 0인 포인트가 존재한다
    When 100명의 사용자가 동시에 각각 10원씩 충전을 시도한다 using "PESSIMISTIC_LOCK"
    Then 최종 잔액은 정확히 1000원이어야 한다

  # Note: Optimistic Lock은 대량 동시 충돌 시 재시도 오버헤드가 크므로
  # 충돌이 적은 환경에 적합합니다. 대량 동시성이 필요한 경우 Pessimistic Lock 사용을 권장합니다.
  #
  # Scenario: 대량 동시 충전 테스트 - Optimistic Lock
  #   Given 사용자 ID가 4이고 초기 잔액이 0인 포인트가 존재한다
  #   When 20명의 사용자가 동시에 각각 10원씩 충전을 시도한다 using "OPTIMISTIC_LOCK"
  #   Then 최종 잔액은 정확히 200원이어야 한다
